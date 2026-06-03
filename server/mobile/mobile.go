package mobile

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"daidai-panel/appboot"
	"daidai-panel/config"
	"daidai-panel/handler"
	"daidai-panel/middleware"
	"daidai-panel/router"
	"daidai-panel/service"

	"github.com/gin-gonic/gin"
)

// ServerState represents the current state of the panel server
type ServerState struct {
	Port      int    `json:"port"`
	Running   bool   `json:"running"`
	URL       string `json:"url"`
	DataDir   string `json:"dataDir"`
	WebDir    string `json:"webDir"`
	Error     string `json:"error,omitempty"`
}

// MobileServer manages the panel server for mobile platforms
type MobileServer struct {
	mu         sync.RWMutex
	server     *http.Server
	listener   net.Listener
	running    bool
	port       int
	dataDir    string
	webDir     string
	cancelFunc context.CancelFunc
}

var (
	defaultServer *MobileServer
	once          sync.Once
)

// GetServer returns the singleton MobileServer instance
func GetServer() *MobileServer {
	once.Do(func() {
		defaultServer = &MobileServer{}
	})
	return defaultServer
}

// Start starts the panel server with the given configuration
// dataDir: directory for storing panel data (database, logs, scripts, etc.)
// webDir: directory containing the frontend static files
// port: port to listen on (0 for auto-select)
func (s *MobileServer) Start(dataDir, webDir string, port int) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return fmt.Errorf("server is already running on port %d", s.port)
	}

	if port <= 0 {
		port = 5701
	}

	// 注入 PATH 环境变量（关键！解决 Android 进程 PATH 为空的问题）
	depsDir := filepath.Join(dataDir, "deps")
	pythonBinDir := filepath.Join(depsDir, "bin", "python", "bin")
	nodeBinDir := filepath.Join(depsDir, "bin", "node", "bin")
	
	currentPath := os.Getenv("PATH")
	newPath := pythonBinDir + ":" + nodeBinDir + ":" + filepath.Join(depsDir, "bin") + ":" + currentPath
	os.Setenv("PATH", newPath)
	log.Printf("[Mobile] PATH injected: %s", newPath)

	// Ensure directories exist
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		return fmt.Errorf("failed to create data directory: %v", err)
	}

	// Generate config.yaml for mobile
	configPath := filepath.Join(dataDir, "config.yaml")
	if err := s.generateConfig(configPath, dataDir, webDir, port); err != nil {
		return fmt.Errorf("failed to generate config: %v", err)
	}

	// Load configuration
	cfg, err := config.Load(configPath)
	if err != nil {
		return fmt.Errorf("failed to load config: %v", err)
	}

	// Setup logging
	panelWriter := s.setupPanelLog(cfg.Data.Dir)
	log.SetOutput(service.NewPanelLogFilterWriter(panelWriter))
	gin.DefaultWriter = service.NewPanelLogFilterWriter(panelWriter)
	gin.DefaultErrorWriter = service.NewPanelLogFilterWriter(panelWriter)

	// Initialize application
	if err := appboot.InitWithConfig(cfg); err != nil {
		return fmt.Errorf("bootstrap failed: %v", err)
	}

	// Initialize services
	service.ReconcileDependenciesAfterRestart()
	handler.FinalizePendingAutoUpdateOnStartup()
	if err := service.EnsureBuiltinNotifyHelpers(cfg.Data.ScriptsDir); err != nil {
		log.Printf("[Mobile] Warning: Failed to ensure builtin notify helpers: %v", err)
	} else {
		log.Printf("[Mobile] Builtin notify helpers ensured in: %s", cfg.Data.ScriptsDir)
	}
	service.CleanupManagedHelperCopiesUnderRoot(cfg.Data.ScriptsDir)
	service.WarmManagedPythonVenv()
	
	// Initialize Alpine + proot environment
	prootMgr := service.GetProotManager()
	// 尝试初始化，如果失败则等待 Java 代码完成
	if err := prootMgr.InitAlpineRootfs(cfg.Data.Dir); err != nil {
		log.Printf("[Mobile] Alpine rootfs not ready yet: %v", err)
	} else {
		log.Printf("[Mobile] Alpine rootfs initialized successfully")
	}

	service.InitSchedulerV2()
	service.InitSubscriptionScheduler()
	service.InitBackupScheduler()
	service.StartResourceWatcher()
	handler.StartPanelAutoUpdateWatcher()

	// Setup Gin engine
	if cfg.Server.Mode == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	engine := gin.New()
	engine.SetTrustedProxies(middleware.CurrentTrustedProxyCIDRs())
	engine.RemoteIPHeaders = []string{"X-Real-IP", "X-Forwarded-For"}
	engine.Use(gin.LoggerWithWriter(service.NewGINLoggerWriter(service.NewPanelLogFilterWriter(panelWriter))))
	engine.Use(gin.Recovery())

	router.Setup(engine)
	s.setupStaticFrontend(engine, cfg.Server.WebDir)

	// Create listener
	addr := fmt.Sprintf(":%d", cfg.Server.Port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %v", addr, err)
	}

	// Start server
	s.server = &http.Server{Handler: engine}
	s.listener = listener
	s.running = true
	s.port = cfg.Server.Port
	s.dataDir = cfg.Data.Dir
	s.webDir = cfg.Server.WebDir

	ctx, cancel := context.WithCancel(context.Background())
	s.cancelFunc = cancel

	go func() {
		if err := s.server.Serve(listener); err != nil && err != http.ErrServerClosed {
			log.Printf("server error: %v", err)
		}
		s.mu.Lock()
		s.running = false
		s.mu.Unlock()
		cancel()
	}()

	// Wait a moment to ensure server starts
	select {
	case <-ctx.Done():
		return fmt.Errorf("server failed to start")
	case <-time.After(100 * time.Millisecond):
		return nil
	}
}

// Stop gracefully stops the panel server
func (s *MobileServer) Stop() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return nil
	}

	// Shutdown services
	service.StopResourceWatcher()
	service.ShutdownSchedulerV2()
	service.ShutdownSubscriptionScheduler()
	service.ShutdownBackupScheduler()
	handler.StopPanelAutoUpdateWatcher()

	// Shutdown HTTP server
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	if err := s.server.Shutdown(ctx); err != nil {
		log.Printf("server shutdown error: %v", err)
		s.server.Close()
	}

	s.running = false
	if s.cancelFunc != nil {
		s.cancelFunc()
	}

	return nil
}

// GetState returns the current state of the server
func (s *MobileServer) GetState() ServerState {
	s.mu.RLock()
	defer s.mu.RUnlock()

	state := ServerState{
		Port:    s.port,
		Running: s.running,
		DataDir: s.dataDir,
		WebDir:  s.webDir,
	}

	if s.running && s.port > 0 {
		state.URL = fmt.Sprintf("http://127.0.0.1:%d", s.port)
	}

	return state
}

// IsRunning returns whether the server is currently running
func (s *MobileServer) IsRunning() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.running
}

// GetPort returns the current port of the server
func (s *MobileServer) GetPort() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.port
}

// GetDataDir returns the data directory of the server
func (s *MobileServer) GetDataDir() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.dataDir
}

func (s *MobileServer) generateConfig(configPath, dataDir, webDir string, port int) error {
	configContent := fmt.Sprintf(`server:
  port: %d
  mode: release
  web_dir: "%s"

database:
  path: "%s"

jwt:
  secret: ""
  access_token_expire: 480h
  refresh_token_expire: 1440h

data:
  dir: "%s"
  scripts_dir: "%s/scripts"
  log_dir: "%s/logs"

cors:
  origins:
    - "*"
`, port, webDir, filepath.Join(dataDir, "daidai.db"), dataDir, dataDir, dataDir)

	return os.WriteFile(configPath, []byte(configContent), 0644)
}

func (s *MobileServer) setupPanelLog(dataDir string) *os.File {
	logFilePath := filepath.Join(dataDir, "panel.log")
	if err := os.MkdirAll(filepath.Dir(logFilePath), 0o755); err != nil {
		return nil
	}

	logFile, err := os.OpenFile(logFilePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil
	}

	return logFile
}

func (s *MobileServer) setupStaticFrontend(engine *gin.Engine, webDir string) {
	if strings.TrimSpace(webDir) == "" {
		return
	}

	absDir, err := filepath.Abs(webDir)
	if err != nil {
		log.Printf("web_dir resolve failed: %v", err)
		return
	}

	indexPath := filepath.Join(absDir, "index.html")
	if _, err := os.Stat(indexPath); err != nil {
		log.Printf("web_dir=%s missing index.html, skip frontend hosting", absDir)
		return
	}

	engine.StaticFile("/", indexPath)
	engine.StaticFile("/favicon.svg", filepath.Join(absDir, "favicon.svg"))

	for _, sub := range []string{"assets", "monaco", "sponsor-portal"} {
		subDir := filepath.Join(absDir, sub)
		if info, err := os.Stat(subDir); err == nil && info.IsDir() {
			engine.Static("/"+sub, subDir)
		}
	}

	engine.NoRoute(func(c *gin.Context) {
		p := c.Request.URL.Path
		if strings.HasPrefix(p, "/api/") {
			c.JSON(404, gin.H{"error": "route not found"})
			return
		}
		c.File(indexPath)
	})

	log.Printf("frontend static directory mounted: %s", absDir)
}
