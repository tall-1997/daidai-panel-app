package service

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
)

// ProotManager 管理 Alpine + rurima 环境
type ProotManager struct {
	mu          sync.Mutex
	initialized bool
	rootfsDir   string
	rurimaBin   string
	dataDir     string
}

var prootManager = &ProotManager{}

// GetProotManager 获取 ProotManager 单例
func GetProotManager() *ProotManager {
	return prootManager
}

// InitAlpineRootfs 初始化 Alpine rootfs
func (pm *ProotManager) InitAlpineRootfs(dataDir string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	if pm.initialized {
		return nil
	}

	pm.dataDir = dataDir
	pm.rootfsDir = filepath.Join(dataDir, "alpine")
	pm.rurimaBin = filepath.Join(dataDir, "rurima")

	// 检查是否已初始化（由 Java 代码完成解压）
	if _, err := os.Stat(filepath.Join(pm.rootfsDir, "bin", "sh")); err == nil {
		pm.initialized = true
		log.Printf("[ProotManager] Alpine rootfs already initialized: %s", pm.rootfsDir)
		return nil
	}

	// Alpine rootfs 尚未解压，等待 Java 代码完成
	log.Printf("[ProotManager] Alpine rootfs not found, waiting for Java to extract...")
	return fmt.Errorf("alpine rootfs not initialized yet")
}

// SetInitialized 标记为已初始化（由 Java 代码调用）
func (pm *ProotManager) SetInitialized(dataDir string, rurimaBin string) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	pm.dataDir = dataDir
	pm.rootfsDir = filepath.Join(dataDir, "alpine")
	pm.rurimaBin = rurimaBin

	pm.initialized = true
	log.Printf("[ProotManager] Alpine rootfs initialized: rootfs=%s, rurima=%s", pm.rootfsDir, pm.rurimaBin)

	// Alpine 初始化完成后，异步创建 Python venv
	go WarmManagedPythonVenv()
}

// IsInitialized 检查是否已初始化
func (pm *ProotManager) IsInitialized() bool {
	return pm.initialized
}

// GetRootfsDir 获取 rootfs 目录
func (pm *ProotManager) GetRootfsDir() string {
	return pm.rootfsDir
}

// ExecInAlpine 在 Alpine 环境中执行命令
// 使用 rurima 进入容器：rurima ruri -p -N -S -A <rootfs> /bin/sh -c '<command>'
func (pm *ProotManager) ExecInAlpine(command string) (string, error) {
	if !pm.initialized {
		return "", fmt.Errorf("Alpine rootfs not initialized")
	}

	// 使用 rurima 进入容器
	// -p: 禁用 proc 挂载（Android 不支持）
	// -N: 禁用网络命名空间
	// -S: 禁用 seccomp
	// -A: Android 模式
	rurimaCmd := fmt.Sprintf("exec '%s' ruri -p -N -S -A '%s' /bin/sh -c '%s'",
		pm.rurimaBin, pm.rootfsDir, command)

	cmd := exec.Command("/system/bin/sh", "-c", rurimaCmd)
	output, err := cmd.CombinedOutput()
	return string(output), err
}

// ExecInAlpineWithEnv 在 Alpine 环境中执行命令（带环境变量）
func (pm *ProotManager) ExecInAlpineWithEnv(command string, env map[string]string) (string, error) {
	if !pm.initialized {
		return "", fmt.Errorf("Alpine rootfs not initialized")
	}

	// 构建环境变量参数
	envArgs := ""
	for k, v := range env {
		envArgs += fmt.Sprintf("export %s='%s'; ", k, v)
	}

	// 使用 rurima 进入容器
	rurimaCmd := fmt.Sprintf("exec '%s' ruri -p -N -S -A '%s' /bin/sh -c '%s%s'",
		pm.rurimaBin, pm.rootfsDir, envArgs, command)

	cmd := exec.Command("/system/bin/sh", "-c", rurimaCmd)
	output, err := cmd.CombinedOutput()
	return string(output), err
}

// ApkInstall 在 Alpine 中安装包
func (pm *ProotManager) ApkInstall(packages ...string) (string, error) {
	cmd := "apk update && apk add " + strings.Join(packages, " ")
	return pm.ExecInAlpine(cmd)
}

// GetPythonPath 获取 Alpine 中的 Python 路径
func (pm *ProotManager) GetPythonPath() string {
	if !pm.initialized {
		return ""
	}
	return filepath.Join(pm.rootfsDir, "usr", "bin", "python3")
}

// GetPipPath 获取 Alpine 中的 pip 路径
func (pm *ProotManager) GetPipPath() string {
	if !pm.initialized {
		return ""
	}
	return filepath.Join(pm.rootfsDir, "usr", "bin", "pip3")
}
