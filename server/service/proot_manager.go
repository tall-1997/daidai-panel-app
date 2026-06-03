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
	"syscall"
	"unsafe"
)

// ProotManager 管理 Alpine + proot 环境
type ProotManager struct {
	mu          sync.Mutex
	initialized bool
	rootfsDir   string
	prootBin    string
	dataDir     string
	prootFdPath string // /proc/self/fd/N 路径，用于从内存执行 proot
}

const memfdCreateSyscall = 279 // SYS_MEMFD_CREATE for arm64

// memfdCreate 创建内存文件描述符
func memfdCreate(name string, flags int) (int, error) {
	namePtr, err := syscall.BytePtrFromString(name)
	if err != nil {
		return 0, err
	}
	fd, _, errno := syscall.Syscall(memfdCreateSyscall, uintptr(unsafe.Pointer(namePtr)), uintptr(flags), 0)
	if errno != 0 {
		return 0, errno
	}
	return int(fd), nil
}

// loadBinaryToMemfd 将二进制文件加载到内存文件描述符

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

// GetProotBin 获取 proot 二进制路径
func (pm *ProotManager) GetProotBin() string {
	return pm.prootBin
}

// SetProotBin 设置 proot 二进制路径
func (pm *ProotManager) SetProotBin(path string) {
	pm.prootBin = path
	log.Printf("[ProotManager] Proot bin path set to: %s", path)
}

// SetInitialized 标记为已初始化（由 Java 代码调用）
func (pm *ProotManager) SetInitialized(dataDir string, prootBin string) {
	pm.mu.Lock()
	defer pm.mu.Unlock()
	
	pm.dataDir = dataDir
	pm.rootfsDir = filepath.Join(dataDir, "alpine")
	pm.prootBin = prootBin
	
	// 尝试使用 memfd_create 将 proot 加载到内存中执行
	// 这样可以绕过 Android 的 noexec 和 SELinux 限制
	if fd, err := loadBinaryToMemfd(prootBin); err == nil {
		pm.prootFdPath = fmt.Sprintf("/proc/self/fd/%d", fd)
		log.Printf("[ProotManager] Proot loaded to memfd: %s", pm.prootFdPath)
	} else {
		log.Printf("[ProotManager] Failed to load proot to memfd: %v, falling back to direct path", err)
		pm.prootFdPath = ""
	}
	
	pm.initialized = true
	log.Printf("[ProotManager] Alpine rootfs initialized by Java: %s, proot: %s, memfd: %s", pm.rootfsDir, pm.prootBin, pm.prootFdPath)
}

// loadBinaryToMemfd 将二进制文件加载到内存文件描述符
func loadBinaryToMemfd(path string) (int, error) {
	// 读取二进制文件
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, fmt.Errorf("failed to read binary: %v", err)
	}
	
	// 创建内存文件描述符
	fd, err := memfdCreate("proot", 0)
	if err != nil {
		return 0, fmt.Errorf("failed to create memfd: %v", err)
	}
	
	// 写入数据到 memfd
	if _, err := syscall.Write(fd, data); err != nil {
		syscall.Close(fd)
		return 0, fmt.Errorf("failed to write to memfd: %v", err)
	}
	
	// 设置为可执行（通过 fchmod）
	if err := syscall.Fchmod(fd, 0755); err != nil {
		log.Printf("[ProotManager] Warning: fchmod failed: %v", err)
	}
	
	return fd, nil
}

// getProotPath 获取 proot 可执行路径
func (pm *ProotManager) getProotPath() string {
	if pm.prootFdPath != "" {
		return pm.prootFdPath
	}
	return pm.prootBin
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

	prootPath := pm.getProotPath()
	prootDir := filepath.Dir(pm.prootBin)
	
	// 使用 sh -c 包装 proot 命令
	prootCmd := fmt.Sprintf("LD_LIBRARY_PATH='%s' exec '%s' -R '%s' -w /root -b /dev -b /proc -b /sys /bin/sh -c '%s'",
		prootDir, prootPath, pm.rootfsDir, command)

	cmd := exec.Command("/system/bin/sh", "-c", prootCmd)
	output, err := cmd.CombinedOutput()
	return string(output), err
}

// ExecInAlpineWithEnv 在 Alpine 环境中执行命令（带环境变量）
func (pm *ProotManager) ExecInAlpineWithEnv(command string, env map[string]string) (string, error) {
	if !pm.initialized {
		return "", fmt.Errorf("Alpine rootfs not initialized")
	}

	prootPath := pm.getProotPath()
	prootDir := filepath.Dir(pm.prootBin)

	// 构建环境变量参数
	envArgs := ""
	for k, v := range env {
		envArgs += fmt.Sprintf("export %s='%s'; ", k, v)
	}

	// 使用 sh -c 包装 proot 命令
	prootCmd := fmt.Sprintf("LD_LIBRARY_PATH='%s' %s exec '%s' -R '%s' -w /root -b /dev -b /proc -b /sys /bin/sh -c '%s'",
		prootDir, envArgs, prootPath, pm.rootfsDir, command)

	cmd := exec.Command("/system/bin/sh", "-c", prootCmd)
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
