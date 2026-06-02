// Package mobile provides gomobile bindings for daidai-panel mobile apps.
package mobile

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"daidai-panel/appboot"
	"daidai-panel/config"
	"daidai-panel/router"
	"daidai-panel/service"

	"github.com/gin-gonic/gin"
)

var (
	instance *MobileServer
	once     sync.Once
)

// MobileServer is the main entry point for mobile platforms.
type MobileServer struct {
	mu        sync.RWMutex
	server    *http.Server
	engine    *gin.Engine
	isRunning bool
	autoStart bool
	dataDir   string
	webDir    string
	port      int
	startTime time.Time
	ctx       context.Context
	cancel    context.CancelFunc
}

// PanelManager is the gomobile-compatible interface for mobile platforms.
type PanelManager struct {
	server *MobileServer
}

// GetInstance returns the singleton PanelManager instance.
func GetInstance() *PanelManager {
	once.Do(func() {
		instance = &MobileServer{
			port: 5701,
		}
		instance.ctx, instance.cancel = context.WithCancel(context.Background())
	})
	return &PanelManager{server: instance}
}

// Initialize sets up the mobile environment.
// dataDir: path to app data directory (e.g., /data/data/com.daidai.panel/files)
// webDir: path to web assets (e.g., assets/web)
func (pm *PanelManager) Initialize(dataDir string, webDir string) error {
	pm.server.mu.Lock()
	defer pm.server.mu.Unlock()

	pm.server.dataDir = dataDir
	pm.server.webDir = webDir

	// Create necessary directories
	for _, dir := range []string{
		dataDir,
		filepath.Join(dataDir, "scripts"),
		filepath.Join(dataDir, "log"),
		filepath.Join(dataDir, "db"),
	} {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return fmt.Errorf("failed to create directory %s: %v", dir, err)
		}
	}

	return nil
}

// StartServer starts the panel HTTP server.
func (pm *PanelManager) StartServer() error {
	pm.server.mu.Lock()
	defer pm.server.mu.Unlock()

	if pm.server.isRunning {
		return fmt.Errorf("server is already running")
	}

	// Create config for mobile
	cfg := &config.Config{
		Server: config.ServerConfig{
			Port:   pm.server.port,
			Mode:   "release",
			WebDir: pm.server.webDir,
		},
		Database: config.DatabaseConfig{
			Path: filepath.Join(pm.server.dataDir, "db", "panel.db"),
		},
		JWT: config.JWTConfig{
			Secret:             generateMobileSecret(pm.server.dataDir),
			AccessTokenExpire:  480 * time.Hour,
			RefreshTokenExpire: 1440 * time.Hour,
		},
		Data: config.DataConfig{
			Dir:        pm.server.dataDir,
			ScriptsDir: filepath.Join(pm.server.dataDir, "scripts"),
			LogDir:     filepath.Join(pm.server.dataDir, "log"),
		},
	}

	// Initialize with config
	if err := appboot.InitWithConfig(cfg); err != nil {
		return fmt.Errorf("initialization failed: %v", err)
	}

	// Initialize scheduler
	service.InitSchedulerV2()
	service.InitSubscriptionScheduler()
	service.InitBackupScheduler()
	service.StartResourceWatcher()

	// Setup Gin
	gin.SetMode(gin.ReleaseMode)
	pm.server.engine = gin.New()
	pm.server.engine.Use(gin.Recovery())

	// Setup routes
	router.Setup(pm.server.engine)

	// Setup static frontend
	pm.setupStaticFrontend()

	// Start server
	addr := fmt.Sprintf(":%d", pm.server.port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %v", addr, err)
	}

	pm.server.server = &http.Server{Handler: pm.server.engine}

	go func() {
		pm.server.mu.Lock()
		pm.server.isRunning = true
		pm.server.startTime = time.Now()
		pm.server.mu.Unlock()

		log.Printf("呆呆面板已启动，端口: %d", pm.server.port)

		if err := pm.server.server.Serve(listener); err != nil && err != http.ErrServerClosed {
			log.Printf("server error: %v", err)
			pm.server.mu.Lock()
			pm.server.isRunning = false
			pm.server.mu.Unlock()
		}
	}()

	// Wait for server to start
	time.Sleep(200 * time.Millisecond)

	return nil
}

// setupStaticFrontend configures static file serving for mobile.
func (pm *PanelManager) setupStaticFrontend() {
	webDir := pm.server.webDir
	if webDir == "" {
		return
	}

	indexPath := filepath.Join(webDir, "index.html")
	if _, err := os.Stat(indexPath); err != nil {
		log.Printf("web_dir=%s missing index.html, skipping frontend", webDir)
		return
	}

	pm.server.engine.StaticFile("/", indexPath)
	pm.server.engine.StaticFile("/favicon.svg", filepath.Join(webDir, "favicon.svg"))

	for _, sub := range []string{"assets", "monaco", "sponsor-portal"} {
		subDir := filepath.Join(webDir, sub)
		if info, err := os.Stat(subDir); err == nil && info.IsDir() {
			pm.server.engine.Static("/"+sub, subDir)
		}
	}

	// SPA fallback
	pm.server.engine.NoRoute(func(c *gin.Context) {
		p := c.Request.URL.Path
		if len(p) >= 4 && p[:4] == "/api" {
			c.JSON(404, gin.H{"error": "route not found"})
			return
		}
		c.File(indexPath)
	})

	log.Printf("frontend static dir mounted: %s", webDir)
}

// StopServer stops the panel HTTP server.
func (pm *PanelManager) StopServer() error {
	pm.server.mu.Lock()
	defer pm.server.mu.Unlock()

	if !pm.server.isRunning {
		return fmt.Errorf("server is not running")
	}

	// Stop schedulers
	service.ShutdownSchedulerV2()
	service.ShutdownSubscriptionScheduler()
	service.ShutdownBackupScheduler()
	service.StopResourceWatcher()

	// Shutdown server
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := pm.server.server.Shutdown(ctx); err != nil {
		log.Printf("shutdown error: %v", err)
		_ = pm.server.server.Close()
	}

	pm.server.isRunning = false
	pm.server.cancel()

	log.Println("呆呆面板已停止")
	return nil
}

// IsRunning returns whether the server is currently running.
func (pm *PanelManager) IsRunning() bool {
	pm.server.mu.RLock()
	defer pm.server.mu.RUnlock()
	return pm.server.isRunning
}

// GetServerURL returns the server URL.
func (pm *PanelManager) GetServerURL() string {
	return fmt.Sprintf("http://127.0.0.1:%d", pm.server.port)
}

// GetPort returns the server port.
func (pm *PanelManager) GetPort() int {
	return pm.server.port
}

// SetPort sets the server port (must be called before StartServer).
func (pm *PanelManager) SetPort(port int) {
	pm.server.mu.Lock()
	defer pm.server.mu.Unlock()
	pm.server.port = port
}

// SetAutoStart configures whether the server should start automatically.
func (pm *PanelManager) SetAutoStart(enabled bool) {
	pm.server.mu.Lock()
	defer pm.server.mu.Unlock()
	pm.server.autoStart = enabled
	// TODO: Persist auto-start setting
}

// IsAutoStart returns whether auto-start is enabled.
func (pm *PanelManager) IsAutoStart() bool {
	pm.server.mu.RLock()
	defer pm.server.mu.RUnlock()
	return pm.server.autoStart
}

// GetUptime returns the server uptime in seconds.
func (pm *PanelManager) GetUptime() int64 {
	pm.server.mu.RLock()
	defer pm.server.mu.RUnlock()

	if !pm.server.isRunning {
		return 0
	}
	return int64(time.Since(pm.server.startTime).Seconds())
}

// GetStatus returns a JSON string with server status.
func (pm *PanelManager) GetStatus() string {
	pm.server.mu.RLock()
	defer pm.server.mu.RUnlock()

	return fmt.Sprintf(`{
		"running": %v,
		"port": %d,
		"autoStart": %v,
		"uptime": %d,
		"dataDir": "%s",
		"webDir": "%s"
	}`,
		pm.server.isRunning,
		pm.server.port,
		pm.server.autoStart,
		pm.GetUptime(),
		pm.server.dataDir,
		pm.server.webDir,
	)
}

// ExportData exports the database data as JSON string.
func (pm *PanelManager) ExportData() (string, error) {
	// TODO: Implement data export from SQLite database
	return "{}", nil
}

// ImportData imports data from a JSON string.
func (pm *PanelManager) ImportData(jsonData string) error {
	// TODO: Implement data import to SQLite database
	return nil
}

// SyncData syncs data with remote server.
func (pm *PanelManager) SyncData(serverURL string, apiKey string) error {
	// TODO: Implement data sync with remote server
	return nil
}

// Cleanup releases resources.
func (pm *PanelManager) Cleanup() {
	if pm.server.isRunning {
		pm.StopServer()
	}
	pm.server.cancel()
}

// generateMobileSecret generates or loads JWT secret for mobile.
func generateMobileSecret(dataDir string) string {
	secretFile := filepath.Join(dataDir, ".jwt_secret")
	if data, err := os.ReadFile(secretFile); err == nil && len(data) > 0 {
		return string(data)
	}

	// Generate new secret
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	secret := hex.EncodeToString(b)

	os.MkdirAll(dataDir, 0755)
	_ = os.WriteFile(secretFile, []byte(secret), 0600)

	return secret
}
