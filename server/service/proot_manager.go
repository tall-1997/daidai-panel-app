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

	// 解压 Alpine rootfs
	log.Printf("[ProotManager] Extracting Alpine rootfs...")
	os.MkdirAll(pm.rootfsDir, 0755)
	
	alpineTarGz := filepath.Join(dataDir, "alpine-rootfs.tar.gz")
	if _, err := os.Stat(alpineTarGz); os.IsNotExist(err) {
		// 尝试从 assets 目录复制
		assetsDir := filepath.Join(filepath.Dir(dataDir), "assets")
		srcFile := filepath.Join(assetsDir, "alpine", "alpine-rootfs.tar.gz")
		if _, err := os.Stat(srcFile); err == nil {
			log.Printf("[ProotManager] Copying from assets: %s", srcFile)
			if err := prootCopyFile(srcFile, alpineTarGz); err != nil {
				return fmt.Errorf("failed to copy alpine rootfs: %v", err)
			}
		} else {
			return fmt.Errorf("alpine rootfs not found at: %s or %s", alpineTarGz, srcFile)
		}
	}

	// 解压
	log.Printf("[ProotManager] Extracting %s to %s", alpineTarGz, pm.rootfsDir)
	cmd := exec.Command("tar", "xzf", alpineTarGz, "-C", pm.rootfsDir)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("failed to extract alpine rootfs: %v, output: %s", err, string(output))
	}

	// 复制 proot
	prootSrc := filepath.Join(filepath.Dir(dataDir), "assets", "alpine", "proot")
	if _, err := os.Stat(prootSrc); err == nil {
		log.Printf("[ProotManager] Copying proot from: %s", prootSrc)
		if err := prootCopyFile(prootSrc, pm.prootBin); err != nil {
			return fmt.Errorf("failed to copy proot: %v", err)
		}
		os.Chmod(pm.prootBin, 0755)
	}

	// 设置 DNS
	dnsContent := "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
	os.WriteFile(filepath.Join(pm.rootfsDir, "etc", "resolv.conf"), []byte(dnsContent), 0644)

	// 验证
	if _, err := os.Stat(filepath.Join(pm.rootfsDir, "bin", "sh")); err != nil {
		return fmt.Errorf("alpine rootfs verification failed: %v", err)
	}

	pm.initialized = true
	log.Printf("[ProotManager] Alpine rootfs initialized successfully")
	return nil
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

// prootCopyFile 复制文件
func prootCopyFile(src, dst string) error {
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

	_, err = io.Copy(out, in)
	return err
}
