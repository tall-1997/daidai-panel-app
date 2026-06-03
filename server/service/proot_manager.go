package service

import (
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
)

// ProotManager 管理 Alpine + proot 环境
type ProotManager struct {
	mu          sync.Mutex
	initialized bool
	rootfsDir   string
	prootBin    string
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
	pm.prootBin = filepath.Join(dataDir, "proot")

	// 检查是否已初始化
	if _, err := os.Stat(filepath.Join(pm.rootfsDir, "bin", "sh")); err == nil {
		pm.initialized = true
		log.Printf("[ProotManager] Alpine rootfs already initialized: %s", pm.rootfsDir)
		return nil
	}

	log.Printf("[ProotManager] Alpine rootfs not found, need to extract from assets")
	return fmt.Errorf("alpine rootfs not initialized, please extract from assets first")
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
func (pm *ProotManager) ExecInAlpine(command string) (string, error) {
	if !pm.initialized {
		return "", fmt.Errorf("Alpine rootfs not initialized")
	}

	// 使用 proot 执行命令
	cmd := exec.Command(pm.prootBin,
		"-R", pm.rootfsDir,
		"-w", "/root",
		"-b", "/dev",
		"-b", "/proc",
		"-b", "/sys",
		"/bin/sh", "-c", command,
	)

	output, err := cmd.CombinedOutput()
	return string(output), err
}

// ExecInAlpineWithEnv 在 Alpine 环境中执行命令（带环境变量）
func (pm *ProotManager) ExecInAlpineWithEnv(command string, env map[string]string) (string, error) {
	if !pm.initialized {
		return "", fmt.Errorf("Alpine rootfs not initialized")
	}

	args := []string{
		"-R", pm.rootfsDir,
		"-w", "/root",
		"-b", "/dev",
		"-b", "/proc",
		"-b", "/sys",
	}

	for k, v := range env {
		args = append(args, "-E", k+"="+v)
	}

	args = append(args, "/bin/sh", "-c", command)

	cmd := exec.Command(pm.prootBin, args...)
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

// GetNodePath 获取 Alpine 中的 Node.js 路径
func (pm *ProotManager) GetNodePath() string {
	if !pm.initialized {
		return ""
	}
	return filepath.Join(pm.rootfsDir, "usr", "bin", "node")
}

// GetNpmPath 获取 Alpine 中的 npm 路径
func (pm *ProotManager) GetNpmPath() string {
	if !pm.initialized {
		return ""
	}
	return filepath.Join(pm.rootfsDir, "usr", "bin", "npm")
}

// extractTarGz 解压 tar.gz 文件
func extractTarGz(src, dst string) error {
	os.MkdirAll(dst, 0755)
	cmd := exec.Command("tar", "xzf", src, "-C", dst)
	return cmd.Run()
}

// copyFile 复制文件
func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()

	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()

	_, err = io.Copy(out, out)
	return err
}
