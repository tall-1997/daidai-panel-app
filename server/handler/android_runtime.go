package handler

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"daidai-panel/config"
	"daidai-panel/middleware"
	"daidai-panel/pkg/response"

	"github.com/gin-gonic/gin"
)

// AndroidRuntimeHandler 提供在 Android 环境里一键安装 Python / Node.js 等脚本运行时的能力。
type AndroidRuntimeHandler struct{}

func NewAndroidRuntimeHandler() *AndroidRuntimeHandler { return &AndroidRuntimeHandler{} }

// getAndroidBinDir 获取 Android 运行时 bin 目录
// 优先使用配置中的数据目录下的 deps/bin，否则使用 Magisk 模块目录
func getAndroidBinDir() string {
	// 如果有配置，使用数据目录下的 deps/bin
	if config.C != nil && strings.TrimSpace(config.C.Data.Dir) != "" {
		return filepath.Join(config.C.Data.Dir, "deps", "bin")
	}
	// 否则使用 Magisk 模块目录
	return "/data/adb/daidai-panel/bin"
}

// androidRuntimePreset 定义了面板预置的运行时下载源。
type androidRuntimePreset struct {
	Name            string `json:"name"`              // python / node
	Label           string `json:"label"`             // 展示用
	Arch            string `json:"arch"`              // arm64 / amd64
	URL             string `json:"url"`               // 下载地址 (tar.gz)
	StripComponents int    `json:"strip_components"`  // 解压时去掉的顶层目录层数
	CheckBin        string `json:"check_bin"`         // 解压后期望存在的可执行文件相对路径 (相对 bin 目录)
	SizeMB          int    `json:"size_mb"`           // 预估大小
	Note            string `json:"note"`              // 备注
}

// 预置下载源
var androidRuntimePresets = []androidRuntimePreset{
	{
		Name:            "python",
		Label:           "Python 3.12 (python-build-standalone)",
		Arch:            "arm64",
		URL:             "https://github.com/indygreg/python-build-standalone/releases/download/20240415/cpython-3.12.3+20240415-aarch64-unknown-linux-gnu-install_only.tar.gz",
		StripComponents: 1,
		CheckBin:        "python/bin/python3",
		SizeMB:          28,
	},
	{
		Name:            "python",
		Label:           "Python 3.12 (python-build-standalone)",
		Arch:            "amd64",
		URL:             "https://github.com/indygreg/python-build-standalone/releases/download/20240415/cpython-3.12.3+20240415-x86_64-unknown-linux-gnu-install_only.tar.gz",
		StripComponents: 1,
		CheckBin:        "python/bin/python3",
		SizeMB:          30,
	},
	{
		Name:            "node",
		Label:           "Node.js v20 LTS (nodejs.org)",
		Arch:            "arm64",
		URL:             "https://nodejs.org/dist/v20.17.0/node-v20.17.0-linux-arm64.tar.gz",
		StripComponents: 1,
		CheckBin:        "node/bin/node",
		SizeMB:          32,
		Note:            "Android bionic libc 下可能需要 Termux 提供的动态库",
	},
	{
		Name:            "node",
		Label:           "Node.js v20 LTS (nodejs.org)",
		Arch:            "amd64",
		URL:             "https://nodejs.org/dist/v20.17.0/node-v20.17.0-linux-x64.tar.gz",
		StripComponents: 1,
		CheckBin:        "node/bin/node",
		SizeMB:          32,
	},
}

type androidRuntimeStatus struct {
	Supported bool                       `json:"supported"`
	Arch      string                     `json:"arch"`
	BinDir    string                     `json:"bin_dir"`
	Termux    bool                       `json:"termux_detected"`
	Runtimes  []androidRuntimeItem       `json:"runtimes"`
	Presets   []androidRuntimePreset     `json:"presets"`
}

type androidRuntimeItem struct {
	Name      string `json:"name"`
	Installed bool   `json:"installed"`
	Path      string `json:"path,omitempty"`
	Version   string `json:"version,omitempty"`
}

// androidSupported 判断当前进程是不是跑在 Android 上。
func androidSupported() bool {
	// 检测 gomobile 版本（应用私有目录）
	if config.C != nil && strings.TrimSpace(config.C.Data.Dir) != "" {
		if strings.HasPrefix(config.C.Data.Dir, "/data/user/") || strings.HasPrefix(config.C.Data.Dir, "/data/data/") {
			log.Printf("[AndroidRuntime] Detected gomobile version, dataDir=%s", config.C.Data.Dir)
			return true
		}
	}
	if runtime.GOOS == "android" {
		log.Printf("[AndroidRuntime] Detected android GOOS")
		return true
	}
	if _, err := os.Stat("/data/adb/modules/daidai-panel"); err == nil {
		log.Printf("[AndroidRuntime] Detected Magisk module")
		return true
	}
	log.Printf("[AndroidRuntime] Not Android environment, GOOS=%s, dataDir=%s", runtime.GOOS, config.C.Data.Dir)
	return false
}

func detectArch() string {
	switch runtime.GOARCH {
	case "arm64":
		return "arm64"
	case "amd64":
		return "amd64"
	}
	return runtime.GOARCH
}

func termuxDetected() bool {
	for _, p := range []string{
		"/data/data/com.termux/files/usr/bin",
		"/data/user/0/com.termux/files/usr/bin",
	} {
		if info, err := os.Stat(p); err == nil && info.IsDir() {
			return true
		}
	}
	return false
}

// probeRuntime 在 androidBinDir + Termux PATH 下查找指定命令。
func probeRuntime(cmdName string) androidRuntimeItem {
	item := androidRuntimeItem{Name: cmdName}
	androidBinDir := getAndroidBinDir()

	candidates := []string{
		filepath.Join(androidBinDir, cmdName, "bin", cmdName),
		filepath.Join(androidBinDir, cmdName),
		filepath.Join("/data/data/com.termux/files/usr/bin", cmdName),
		filepath.Join("/usr/bin", cmdName),
	}
	if cmdName == "python" {
		candidates = append([]string{
			filepath.Join(androidBinDir, "python", "bin", "python3"),
			filepath.Join(androidBinDir, "python3"),
		}, candidates...)
	}

	for _, c := range candidates {
		info, err := os.Stat(c)
		if err != nil || info.IsDir() {
			continue
		}
		if info.Mode()&0o111 == 0 {
			continue
		}
		item.Installed = true
		item.Path = c

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		out, err := exec.CommandContext(ctx, c, "--version").CombinedOutput()
		cancel()
		if err == nil {
			item.Version = strings.TrimSpace(string(out))
		}
		return item
	}
	return item
}

func (h *AndroidRuntimeHandler) Status(c *gin.Context) {
	androidBinDir := getAndroidBinDir()
	supported := androidSupported()
	
	log.Printf("[AndroidRuntime] Status called, supported=%v, binDir=%s", supported, androidBinDir)
	
	if !supported {
		response.Success(c, androidRuntimeStatus{
			Supported: false,
			Arch:      detectArch(),
			BinDir:    androidBinDir,
		})
		return
	}

	arch := detectArch()
	runtimes := []androidRuntimeItem{
		probeRuntime("python"),
		probeRuntime("node"),
	}

	var presets []androidRuntimePreset
	for _, p := range androidRuntimePresets {
		if p.Arch == arch {
			presets = append(presets, p)
		}
	}

	result := androidRuntimeStatus{
		Supported: true,
		Arch:      arch,
		BinDir:    androidBinDir,
		Termux:    termuxDetected(),
		Runtimes:  runtimes,
		Presets:   presets,
	}
	
	log.Printf("[AndroidRuntime] Status result: supported=%v, runtimes=%d, presets=%d", result.Supported, len(result.Runtimes), len(result.Presets))
	
	response.Success(c, result)
}

type androidInstallRequest struct {
	Name            string `json:"name" binding:"required"`        // python / node
	URL             string `json:"url"`                             // 可选：自定义下载源
	StripComponents int    `json:"strip_components"`                // 解压层数
}

// Install 以 SSE 形式流式返回下载/解压进度。
func (h *AndroidRuntimeHandler) Install(c *gin.Context) {
	androidBinDir := getAndroidBinDir()
	
	if !androidSupported() {
		response.Error(c, http.StatusForbidden, "仅 Android 环境支持该操作")
		return
	}

	var req androidInstallRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.Error(c, http.StatusBadRequest, "参数错误: "+err.Error())
		return
	}

	if req.Name != "python" && req.Name != "node" {
		response.Error(c, http.StatusBadRequest, "name 只能是 python 或 node")
		return
	}

	// 如果没传 URL，从预置里挑匹配当前 arch 的
	if strings.TrimSpace(req.URL) == "" {
		arch := detectArch()
		for _, p := range androidRuntimePresets {
			if p.Name == req.Name && p.Arch == arch {
				req.URL = p.URL
				if req.StripComponents == 0 {
					req.StripComponents = p.StripComponents
				}
				break
			}
		}
	}
	if req.URL == "" {
		response.Error(c, http.StatusBadRequest, "当前架构没有预置下载源，请手动填写 url")
		return
	}

	c.Writer.Header().Set("Content-Type", "text/event-stream")
	c.Writer.Header().Set("Cache-Control", "no-cache")
	c.Writer.Header().Set("Connection", "keep-alive")
	c.Writer.WriteHeader(http.StatusOK)
	flusher, _ := c.Writer.(http.Flusher)
	emit := func(msg string) {
		fmt.Fprintf(c.Writer, "data: %s\n\n", strings.ReplaceAll(msg, "\n", "\\n"))
		if flusher != nil {
			flusher.Flush()
		}
	}

	emit(fmt.Sprintf("下载目标: %s", req.URL))
	emit(fmt.Sprintf("解压到: %s/%s", androidBinDir, req.Name))

	// 检查目录权限
	parentDir := filepath.Dir(androidBinDir)
	if err := os.MkdirAll(parentDir, 0o755); err != nil {
		emit("❌ 无法创建父目录: " + parentDir + " - " + err.Error())
		log.Printf("[AndroidRuntime] Failed to create parent dir: %v", err)
		return
	}
	
	if err := os.MkdirAll(androidBinDir, 0o755); err != nil {
		emit("❌ 无法创建目标目录: " + err.Error())
		log.Printf("[AndroidRuntime] Failed to create bin dir: %v", err)
		return
	}
	
	// 检查目录是否可写
	testFile := filepath.Join(androidBinDir, ".test_write")
	if err := os.WriteFile(testFile, []byte("test"), 0o644); err != nil {
		emit("❌ 目录不可写: " + err.Error())
		log.Printf("[AndroidRuntime] Directory not writable: %v", err)
		return
	}
	os.Remove(testFile)
	log.Printf("[AndroidRuntime] Directory is writable: %s", androidBinDir)

	targetDir := filepath.Join(androidBinDir, req.Name)
	// 清理旧目录
	if _, err := os.Stat(targetDir); err == nil {
		emit("清理旧版本: " + targetDir)
		if err := os.RemoveAll(targetDir); err != nil {
			emit("❌ 清理失败: " + err.Error())
			return
		}
	}
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		emit("❌ 创建目标目录失败: " + err.Error())
		return
	}

	client := &http.Client{Timeout: 30 * time.Minute}
	resp, err := client.Get(req.URL)
	if err != nil {
		emit("❌ 下载失败: " + err.Error())
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		emit(fmt.Sprintf("❌ HTTP %d", resp.StatusCode))
		return
	}

	emit(fmt.Sprintf("连接成功，Content-Length=%d", resp.ContentLength))

	gzr, err := gzip.NewReader(resp.Body)
	if err != nil {
		emit("❌ 解压 gzip 失败: " + err.Error())
		return
	}
	defer gzr.Close()

	tr := tar.NewReader(gzr)
	fileCount := 0
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			emit("❌ 读取 tar 失败: " + err.Error())
			return
		}

		// strip components
		name := hdr.Name
		for i := 0; i < req.StripComponents; i++ {
			if idx := strings.Index(name, "/"); idx >= 0 {
				name = name[idx+1:]
			}
		}
		if name == "" {
			continue
		}

		target := filepath.Join(targetDir, name)
		log.Printf("[AndroidRuntime] Extracting: %s", target)

		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				emit("❌ 创建目录失败: " + target + " - " + err.Error())
				log.Printf("[AndroidRuntime] Failed to create dir: %v", err)
				return
			}
		case tar.TypeReg:
			parentDir := filepath.Dir(target)
			if err := os.MkdirAll(parentDir, 0o755); err != nil {
				emit("❌ 创建父目录失败: " + parentDir + " - " + err.Error())
				log.Printf("[AndroidRuntime] Failed to create parent dir: %v", err)
				return
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY, os.FileMode(hdr.Mode))
			if err != nil {
				emit("❌ 创建文件失败: " + target + " - " + err.Error())
				log.Printf("[AndroidRuntime] Failed to create file: %v", err)
				return
			}
			written, err := io.Copy(f, tr)
			f.Close()
			if err != nil {
				emit("❌ 写入文件失败: " + target + " - " + err.Error())
				log.Printf("[AndroidRuntime] Failed to write file: %v", err)
				return
			}
			log.Printf("[AndroidRuntime] Extracted: %s (%d bytes)", target, written)
			fileCount++
			if fileCount%100 == 0 {
				emit(fmt.Sprintf("已解压 %d 个文件...", fileCount))
			}
		}
	}

	emit(fmt.Sprintf("✅ 安装完成，共解压 %d 个文件", fileCount))
	emit(fmt.Sprintf("安装位置: %s/%s", androidBinDir, req.Name))
}

type androidUninstallRequest struct {
	Name string `json:"name" binding:"required"`
}

func (h *AndroidRuntimeHandler) Uninstall(c *gin.Context) {
	androidBinDir := getAndroidBinDir()
	
	if !androidSupported() {
		response.Error(c, http.StatusForbidden, "仅 Android 环境支持该操作")
		return
	}

	var req androidUninstallRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.Error(c, http.StatusBadRequest, "参数错误: "+err.Error())
		return
	}

	target := filepath.Join(androidBinDir, req.Name)
	if _, err := os.Stat(target); os.IsNotExist(err) {
		response.Error(c, http.StatusNotFound, "未找到该运行时")
		return
	}

	if err := os.RemoveAll(target); err != nil {
		response.Error(c, http.StatusInternalServerError, "卸载失败: "+err.Error())
		return
	}

	response.Success(c, gin.H{"message": "已卸载 " + req.Name})
}

var androidInstallMu sync.Mutex

func (h *AndroidRuntimeHandler) Lock() bool {
	if !androidInstallMu.TryLock() {
		return false
	}
	return true
}

func (h *AndroidRuntimeHandler) Unlock() {
	androidInstallMu.Unlock()
}

func (h *AndroidRuntimeHandler) RegisterRoutes(r *gin.RouterGroup) {
	g := r.Group("/android-runtime", middleware.JWTAuth(), middleware.RequireAdmin())
	g.GET("/status", h.Status)
	g.POST("/install", h.Install)
	g.POST("/uninstall", h.Uninstall)
}
