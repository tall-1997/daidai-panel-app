package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"daidai-panel/appboot"
	"daidai-panel/config"
	"daidai-panel/handler"
	"daidai-panel/middleware"
	"daidai-panel/router"
	"daidai-panel/service"

	"github.com/gin-gonic/gin"
)

func main() {
	// 立即输出到stderr，确保Android能捕获
	fmt.Fprintf(os.Stderr, "[daidai] 进程启动\n")

	dataDir := flag.String("data-dir", "", "数据目录路径")
	webDir := flag.String("web-dir", "", "前端资源目录路径")
	port := flag.Int("port", 5701, "监听端口")
	flag.Parse()

	fmt.Fprintf(os.Stderr, "[daidai] 参数: data-dir=%s, web-dir=%s, port=%d\n", *dataDir, *webDir, *port)

	if *dataDir == "" {
		fmt.Fprintf(os.Stderr, "[daidai] 错误: 必须指定 -data-dir 参数\n")
		os.Exit(1)
	}

	// 注入 PATH 环境变量（关键！解决 Android 进程 PATH 为空的问题）
	depsDir := filepath.Join(*dataDir, "deps")
	pythonBinDir := filepath.Join(depsDir, "bin", "python", "bin")
	nodeBinDir := filepath.Join(depsDir, "bin", "node", "bin")
	
	currentPath := os.Getenv("PATH")
	newPath := pythonBinDir + ":" + nodeBinDir + ":" + depsDir + "/bin:" + currentPath
	os.Setenv("PATH", newPath)
	fmt.Fprintf(os.Stderr, "[daidai] PATH 已注入: %s\n", newPath)

	// 确保目录存在
	if err := os.MkdirAll(*dataDir, 0755); err != nil {
		fmt.Fprintf(os.Stderr, "[daidai] 创建数据目录失败: %v\n", err)
		os.Exit(1)
	}

	// 生成配置文件
	configPath := filepath.Join(*dataDir, "config.yaml")
	fmt.Fprintf(os.Stderr, "[daidai] 生成配置文件: %s\n", configPath)
	if err := generateConfig(configPath, *dataDir, *webDir, *port); err != nil {
		fmt.Fprintf(os.Stderr, "[daidai] 生成配置文件失败: %v\n", err)
		os.Exit(1)
	}

	// 加载配置
	fmt.Fprintf(os.Stderr, "[daidai] 加载配置...\n")
	cfg, err := config.Load(configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "[daidai] 加载配置失败: %v\n", err)
		os.Exit(1)
	}

	panelWriter := setupPanelLog(cfg.Data.Dir)
	log.SetOutput(service.NewPanelLogFilterWriter(panelWriter))
	gin.DefaultWriter = service.NewPanelLogFilterWriter(panelWriter)
	gin.DefaultErrorWriter = service.NewPanelLogFilterWriter(panelWriter)

	fmt.Fprintf(os.Stderr, "[daidai] 初始化应用...\n")
	if err := appboot.InitWithConfig(cfg); err != nil {
		fmt.Fprintf(os.Stderr, "[daidai] 初始化失败: %v\n", err)
		os.Exit(1)
	}

	service.ReconcileDependenciesAfterRestart()
	handler.FinalizePendingAutoUpdateOnStartup()
	service.EnsureBuiltinNotifyHelpers(cfg.Data.ScriptsDir)
	service.CleanupManagedHelperCopiesUnderRoot(cfg.Data.ScriptsDir)
	service.WarmManagedPythonVenv()

	service.InitSchedulerV2()
	defer service.ShutdownSchedulerV2()

	service.InitSubscriptionScheduler()
	defer service.ShutdownSubscriptionScheduler()

	service.InitBackupScheduler()
	defer service.ShutdownBackupScheduler()

	service.StartResourceWatcher()
	defer service.StopResourceWatcher()

	handler.StartPanelAutoUpdateWatcher()
	defer handler.StopPanelAutoUpdateWatcher()

	if cfg.Server.Mode == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	engine := gin.New()
	engine.SetTrustedProxies(middleware.CurrentTrustedProxyCIDRs())
	engine.RemoteIPHeaders = []string{"X-Real-IP", "X-Forwarded-For"}
	engine.Use(gin.LoggerWithWriter(service.NewGINLoggerWriter(service.NewPanelLogFilterWriter(panelWriter))))
	engine.Use(gin.Recovery())

	router.Setup(engine)
	setupStaticFrontend(engine, cfg.Server.WebDir)

	addr := fmt.Sprintf(":%d", cfg.Server.Port)
	fmt.Fprintf(os.Stderr, "[daidai] 监听端口: %s\n", addr)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "[daidai] 监听失败: %v\n", err)
		os.Exit(1)
	}

	// 关键：输出到stdout，确保Android能捕获
	fmt.Println("呆呆面板已启动，端口: " + fmt.Sprintf("%d", cfg.Server.Port))
	fmt.Fprintf(os.Stderr, "[daidai] 服务器就绪\n")

	server := &http.Server{Handler: engine}
	serverErr := make(chan error, 1)
	go func() {
		serverErr <- server.Serve(listener)
	}()

	shutdownSignals := make(chan os.Signal, 1)
	signal.Notify(shutdownSignals, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(shutdownSignals)

	select {
	case err := <-serverErr:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			fmt.Fprintf(os.Stderr, "[daidai] 服务器错误: %v\n", err)
			os.Exit(1)
		}
	case sig := <-shutdownSignals:
		fmt.Fprintf(os.Stderr, "[daidai] 收到信号 %s，正在关闭...\n", sig)
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			fmt.Fprintf(os.Stderr, "[daidai] 关闭失败: %v\n", err)
			_ = server.Close()
		}
	}
}

func generateConfig(configPath, dataDir, webDir string, port int) error {
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

func setupPanelLog(dataDir string) io.Writer {
	logFilePath := filepath.Join(dataDir, "panel.log")
	if err := os.MkdirAll(filepath.Dir(logFilePath), 0o755); err != nil {
		return os.Stdout
	}

	logFile, err := os.OpenFile(logFilePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return os.Stdout
	}

	switch service.ResolvePanelRuntimeMode() {
	case service.PanelRuntimeModeStdout:
		return io.MultiWriter(os.Stdout, logFile)
	default:
		return logFile
	}
}

func setupStaticFrontend(engine *gin.Engine, webDir string) {
	if strings.TrimSpace(webDir) == "" {
		return
	}

	absDir, err := filepath.Abs(webDir)
	if err != nil {
		log.Printf("web_dir 解析失败: %v", err)
		return
	}

	indexPath := filepath.Join(absDir, "index.html")
	if _, err := os.Stat(indexPath); err != nil {
		log.Printf("web_dir=%s 缺少 index.html，跳过前端托管", absDir)
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

	log.Printf("前端静态目录已挂载: %s", absDir)
}
