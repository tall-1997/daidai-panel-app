package service

import (
	"bufio"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"daidai-panel/config"
	"daidai-panel/database"
	"daidai-panel/model"
)

type managedRuntimePaths struct {
	NodeBin          string
	NodeModules      string
	VenvBin          string
	VenvSitePackages string
	SanitizedPath    string

	searchDirs []string
}

var managedPythonVenvMu sync.Mutex

var windowsShellSearchDirs = []string{
	filepath.Join(os.Getenv("ProgramFiles"), "Git", "bin"),
	filepath.Join(os.Getenv("ProgramFiles"), "Git", "usr", "bin"),
	filepath.Join(os.Getenv("ProgramFiles(x86)"), "Git", "bin"),
	filepath.Join(os.Getenv("ProgramFiles(x86)"), "Git", "usr", "bin"),
}

var windowsPythonPreferredDirs = []string{
	filepath.Join(os.Getenv("LocalAppData"), "Programs", "Python", "Python314"),
	filepath.Join(os.Getenv("LocalAppData"), "Programs", "Python", "Python313"),
	filepath.Join(os.Getenv("LocalAppData"), "Programs", "Python", "Python312"),
	filepath.Join(os.Getenv("LocalAppData"), "Programs", "Python", "Python311"),
	filepath.Join(os.Getenv("LocalAppData"), "Programs", "Python", "Python310"),
}

// pythonEnvBootstrap 只负责三件事：
//  1. 从 env.json 注入任务环境变量到 os.environ
//  2. 把 PYTHONPATH 里声明的目录前置到 sys.path（工作目录、脚本目录、venv site-packages）
//  3. 以 runpy.run_path 的方式执行用户脚本
//
// 历史上这里还有"AST 扫 import + importlib.find_spec 判缺失 + 自动 pip install"
// 的预检链路（v2.0.7 引入），但 find_spec 在 Alpine venv 下对 pysmx 等包会漏判，
// 导致已装好的包反复被判定缺失、循环触发 pip install。真实缺失的包由
// Go 侧 task_executor.detectAndInstallDeps 兜底——它在脚本真实抛出
// ModuleNotFoundError 时介入，基于正则抓模块名后 pip install，并自动重跑脚本，
// 比预检更精准，且最多重试 5 次覆盖多依赖场景。
const pythonEnvBootstrap = `import json, os, runpy, sys
env_file, script_path, extra_path_raw = sys.argv[1:4]
script_args = sys.argv[4:]
with open(env_file, "r", encoding="utf-8") as fh:
    payload = json.load(fh)
for key, value in payload.items():
    if value is None:
        continue
    os.environ[str(key)] = str(value)
for entry in reversed([item for item in extra_path_raw.split(os.pathsep) if item]):
    if entry not in sys.path:
        sys.path.insert(0, entry)
sys.argv = [script_path] + script_args
runpy.run_path(script_path, run_name="__main__")
`

func BuildManagedRuntimeEnvMap(workDir, scriptsDir string, defaultChannelID *uint, ttl time.Duration) (map[string]string, error) {
	var envVarRecords []model.EnvVar
	// 按稳定顺序读取：置顶 > 组内位置 > 创建时间 > id；避免无 ORDER BY 导致同名变量的相对顺序抖动
	database.DB.Where("enabled = ?", true).
		Order("sort_order DESC, position ASC, created_at ASC, id ASC").
		Find(&envVarRecords)

	// 先按 name 分组保持顺序，再用 joinTaskEnvValues 做带转义合并，
	// 解决值内含 '&' 时脚本按 '&' 切分会错位的问题（与 splitTaskEnvValues 对称）。
	grouped := make(map[string][]string)
	order := make([]string, 0, len(envVarRecords))
	for _, ev := range envVarRecords {
		if _, ok := grouped[ev.Name]; !ok {
			order = append(order, ev.Name)
		}
		grouped[ev.Name] = append(grouped[ev.Name], ev.Value)
	}

	envMap := make(map[string]string, len(grouped))
	for _, name := range order {
		envMap[name] = joinTaskEnvValues(grouped[name])
	}

	loadConfigShellVars(envMap)

	runtimePaths := currentManagedRuntimePaths()
	if runtimePaths.NodeModules != "" {
		envMap["NODE_PATH"] = runtimePaths.NodeModules
	}
	if runtimePaths.SanitizedPath != "" {
		envMap["PATH"] = joinPathSegments(runtimePaths.VenvBin, runtimePaths.SanitizedPath, runtimePaths.NodeBin)
	}
	if pythonPath := buildManagedPythonPath(envMap["PYTHONPATH"], workDir, scriptsDir, runtimePaths.VenvSitePackages); pythonPath != "" {
		envMap["PYTHONPATH"] = pythonPath
	}
	AppendScriptHelperPaths(envMap, scriptsDir)
	var helperErr error
	if helperEnv, err := BuildNotifyHelperEnv(scriptsDir, workDir, config.C.Server.Port, defaultChannelID, ttl); err == nil {
		for key, value := range helperEnv {
			envMap[key] = value
		}
	} else {
		helperErr = err
	}

	return envMap, helperErr
}

func buildManagedPythonPath(existingPythonPath, workDir, scriptsDir, venvSitePackages string) string {
	return joinPathSegments(workDir, scriptsDir, existingPythonPath, venvSitePackages)
}

func CreateManagedCommand(interpreter, scriptPath string, scriptArgs []string, workDir string, envVars map[string]string) (*exec.Cmd, func(), error) {
	runtimePaths := currentManagedRuntimePaths()
	
	// 检查 Alpine 环境是否可用
	prootMgr := GetProotManager()
	if prootMgr.IsInitialized() {
		// 使用 Alpine + proot 执行脚本
		log.Printf("[CreateManagedCommand] Using Alpine + proot for %s", interpreter)
		return createProotCommand(interpreter, scriptPath, scriptArgs, workDir, envVars)
	}

	switch interpreter {
	case "python", "python3":
		return createManagedPythonCommand(scriptPath, scriptArgs, workDir, envVars, runtimePaths)
	case "node":
		return createManagedNodeCommand(scriptPath, scriptArgs, workDir, envVars, runtimePaths)
	case "ts-node":
		return createManagedTSNodeCommand(scriptPath, scriptArgs, workDir, envVars, runtimePaths)
	default:
		return createStandardManagedCommand(interpreter, scriptPath, scriptArgs, workDir, envVars, runtimePaths)
	}
}

// createProotCommand 使用 Alpine + proot 创建命令
func createProotCommand(interpreter, scriptPath string, scriptArgs []string, workDir string, envVars map[string]string) (*exec.Cmd, func(), error) {
	prootMgr := GetProotManager()
	prootPath := prootMgr.getProotPath()
	prootBin := prootMgr.prootBin
	rootfsDir := prootMgr.rootfsDir
	prootDir := filepath.Dir(prootBin)
	
	// 构建 proot 参数
	prootArgs := []string{
		"-R", rootfsDir,
		"-w", "/tmp",
		"-b", "/dev",
		"-b", "/proc",
		"-b", "/sys",
	}
	
	// 添加环境变量
	for k, v := range envVars {
		prootArgs = append(prootArgs, "-E", k+"="+v)
	}
	
	// 构建要执行的命令
	var shellCmd string
	switch interpreter {
	case "python", "python3":
		shellCmd = fmt.Sprintf("cd /tmp && python3 %s %s", scriptPath, strings.Join(scriptArgs, " "))
	case "node":
		shellCmd = fmt.Sprintf("cd /tmp && node %s %s", scriptPath, strings.Join(scriptArgs, " "))
	default:
		shellCmd = fmt.Sprintf("cd /tmp && %s %s %s", interpreter, scriptPath, strings.Join(scriptArgs, " "))
	}
	
	prootArgs = append(prootArgs, "/bin/sh", "-c", shellCmd)
	
	// 使用 sh -c 包装 proot 命令，设置 LD_LIBRARY_PATH
	argsStr := strings.Join(prootArgs, " ")
	prootCmd := fmt.Sprintf("LD_LIBRARY_PATH='%s' exec '%s' %s", prootDir, prootPath, argsStr)
	
	cmd := exec.Command("/system/bin/sh", "-c", prootCmd)
	cmd.Dir = workDir
	
	cleanup := func() {}
	return cmd, cleanup, nil
}

func currentManagedRuntimePaths() managedRuntimePaths {
	dataDir := ""
	if config.C != nil {
		dataDir = config.C.Data.Dir
	}
	depsDir := filepath.Join(dataDir, "deps")
	venvDir := filepath.Join(depsDir, "python", "venv")
	venvBin := resolveManagedVenvBin(venvDir)
	nodeBin := filepath.Join(depsDir, "nodejs", "node_modules", ".bin")
	
	// Android 运行时安装目录
	androidBinDir := filepath.Join(depsDir, "bin")
	androidPythonBin := filepath.Join(androidBinDir, "python", "bin")
	androidNodeBin := filepath.Join(androidBinDir, "node", "bin")
	
	// 构建 PATH：始终包含 Android 运行时路径（无论目录是否存在）
	// 这样当运行时安装后，PATH 就能正确找到
	pathParts := []string{androidPythonBin, androidNodeBin, androidBinDir}
	
	// 添加现有 PATH
	existingPath := os.Getenv("PATH")
	if existingPath != "" {
		pathParts = append(pathParts, existingPath)
	}
	
	// 添加 venv 和 node bin（如果存在）
	if venvBin != "" {
		pathParts = append(pathParts, venvBin)
	}
	if nodeBin != "" {
		pathParts = append(pathParts, nodeBin)
	}
	
	sanitizedPath := strings.Join(pathParts, string(os.PathListSeparator))
	log.Printf("[currentManagedRuntimePaths] PATH: %s", sanitizedPath)

	return managedRuntimePaths{
		NodeBin:          nodeBin,
		NodeModules:      filepath.Join(depsDir, "nodejs", "node_modules"),
		VenvBin:          venvBin,
		VenvSitePackages: findVenvSitePackages(venvDir),
		SanitizedPath:    sanitizedPath,
		searchDirs:       splitPathDirs(sanitizedPath),
	}
}

// fixPythonPermissionsRecursive 递归修复Python目录权限（包含SELinux上下文）
func fixPythonPermissionsRecursive(pythonRoot string) error {
	// 1. 递归给所有文件添加用户可执行权限
	err := filepath.Walk(pythonRoot, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		return os.Chmod(path, 0755)
	})
	if err != nil {
		return err
	}

	// 2. 修复SELinux上下文（Android专属，必须加）
	cmd := exec.Command("chcon", "-R", "u:object_r:app_data_file:s0", pythonRoot)
	if runErr := cmd.Run(); runErr != nil {
		log.Printf("[fixPythonPermissions] chcon failed (may not be supported): %v", runErr)
		// chcon 失败不算致命错误
	}

	log.Printf("[fixPythonPermissions] Permissions fixed for: %s", pythonRoot)
	return nil
}

// ensurePythonReady 执行python/pip命令前必须调用
func ensurePythonReady() error {
	dataDir := ""
	if config.C != nil {
		dataDir = config.C.Data.Dir
	}
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("data dir not configured")
	}

	pythonBinDir := filepath.Join(dataDir, "deps", "bin", "python", "bin")

	// 检查核心解释器是否有执行权限
	interpreters := []string{"python3.12", "python3", "python", "pip3"}
	needFix := false

	for _, bin := range interpreters {
		path := filepath.Join(pythonBinDir, bin)
		info, err := os.Stat(path)
		if err != nil {
			continue
		}
		if info.Mode()&0100 == 0 {
			log.Printf("[ensurePythonReady] %s has no execute permission, need fix", bin)
			needFix = true
			break
		}
	}

	if needFix {
		log.Printf("[ensurePythonReady] Detected Python files have no execute permission, fixing...")
		return fixPythonPermissionsRecursive(pythonBinDir)
	}
	return nil
}

// 虚拟环境重试计数器
var venvRetryCount = 0
const maxVenvRetries = 3

func ensureManagedPythonVenv(syncCreate bool) bool {
	dataDir := ""
	if config.C != nil {
		dataDir = config.C.Data.Dir
	}
	if strings.TrimSpace(dataDir) == "" {
		return false
	}

	venvDir := filepath.Join(dataDir, "deps", "python", "venv")
	if info, err := os.Stat(resolveManagedVenvBin(venvDir)); err == nil && info.IsDir() {
		return true
	}

	if !syncCreate {
		go ensureManagedPythonVenv(true)
		return false
	}

	managedPythonVenvMu.Lock()
	defer managedPythonVenvMu.Unlock()

	if info, err := os.Stat(resolveManagedVenvBin(venvDir)); err == nil && info.IsDir() {
		log.Printf("[ensureManagedPythonVenv] Using prebuilt venv at: %s", venvDir)
		return true
	}

	// 检查是否有预编译的 venv 脚本（由 Java 解压）
	prebuiltPython := filepath.Join(venvDir, "bin", "python")
	if fileExists(prebuiltPython) {
		log.Printf("[ensureManagedPythonVenv] Prebuilt venv scripts found, skipping proot creation")
		return true
	}

	// 检查重试次数，避免无限循环
	if venvRetryCount >= maxVenvRetries {
		log.Printf("[ensureManagedPythonVenv] Max retries (%d) reached, skipping venv creation", maxVenvRetries)
		return false
	}

	_ = os.MkdirAll(filepath.Dir(venvDir), 0o755)
	
	// 硬编码绝对路径
	python3Bin := filepath.Join(dataDir, "deps", "bin", "python", "bin", "python3.12")
	pythonBin := filepath.Join(dataDir, "deps", "bin", "python", "bin", "python")
	python3Link := filepath.Join(dataDir, "deps", "bin", "python", "bin", "python3")
	
	// 关键：执行前强制修复权限
	if err := ensurePythonReady(); err != nil {
		log.Printf("[ensureManagedPythonVenv] Failed to fix permissions: %v", err)
		venvRetryCount++
		return false
	}
	
	log.Printf("[ensureManagedPythonVenv] Creating venv at: %s", venvDir)
	log.Printf("[ensureManagedPythonVenv] python3.12: %s (exists: %v)", python3Bin, fileExists(python3Bin))
	log.Printf("[ensureManagedPythonVenv] python: %s (exists: %v)", pythonBin, fileExists(pythonBin))
	log.Printf("[ensureManagedPythonVenv] python3: %s (exists: %v)", python3Link, fileExists(python3Link))
	
	// 尝试的顺序：python3.12 -> python3 -> python
	pythonCandidates := []string{python3Bin, python3Link, pythonBin}
	
	var lastErr error
	for _, pythonPath := range pythonCandidates {
		if !fileExists(pythonPath) {
			log.Printf("[ensureManagedPythonVenv] Skip %s (not exists)", pythonPath)
			continue
		}
		
		// 使用 proot 在 Alpine 容器内执行
		prootMgr := GetProotManager()
		if prootMgr.IsInitialized() {
			shellCmd := pythonPath + " -m venv " + venvDir
			log.Printf("[ensureManagedPythonVenv] Trying via proot: %s", shellCmd)
			output, err := prootMgr.ExecInAlpine(shellCmd)
			if err == nil {
				log.Printf("[ensureManagedPythonVenv] Venv created successfully via proot")
				venvRetryCount = 0
				return true
			}
			lastErr = fmt.Errorf("proot exec failed: %v: %s", err, output)
			log.Printf("[ensureManagedPythonVenv] %s failed: %v", pythonPath, lastErr)
		} else {
			// 回退到直接执行
			os.Chmod(pythonPath, 0755)
			shellCmd := pythonPath + " -m venv " + venvDir
			log.Printf("[ensureManagedPythonVenv] Trying direct: %s", shellCmd)
			cmd := exec.Command("/system/bin/sh", "-c", shellCmd)
			out, runErr := cmd.CombinedOutput()
			if runErr == nil {
				log.Printf("[ensureManagedPythonVenv] Venv created successfully")
				venvRetryCount = 0
				return true
			}
			lastErr = fmt.Errorf("%s -m venv failed: %v: %s", pythonPath, runErr, strings.TrimSpace(string(out)))
			log.Printf("[ensureManagedPythonVenv] %s failed: %v", pythonPath, lastErr)
		}
	}
	
	if lastErr != nil {
		venvRetryCount++
		log.Printf("warn: managed python venv create failed (attempt %d/%d): %v", venvRetryCount, maxVenvRetries, lastErr)
	}
	return false
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func EnsureManagedPythonVenv() bool {
	return ensureManagedPythonVenv(true)
}

func WarmManagedPythonVenv() {
	ensureManagedPythonVenv(false)
}

func resolveManagedVenvBin(venvDir string) string {
	venvDir = strings.TrimSpace(venvDir)
	if venvDir == "" {
		return ""
	}

	candidates := []string{
		filepath.Join(venvDir, "Scripts"),
		filepath.Join(venvDir, "bin"),
	}
	if runtime.GOOS != "windows" {
		candidates[0], candidates[1] = candidates[1], candidates[0]
	}

	for _, candidate := range candidates {
		if info, err := os.Stat(candidate); err == nil && info.IsDir() {
			return candidate
		}
	}

	if runtime.GOOS == "windows" {
		return filepath.Join(venvDir, "Scripts")
	}
	return filepath.Join(venvDir, "bin")
}

func ResolveManagedPipBinary() string {
	EnsureManagedPythonVenv()
	runtimePaths := currentManagedRuntimePaths()
	for _, name := range []string{"pip3", "pip"} {
		if binary := findExecutableInDir(runtimePaths.VenvBin, name); binary != "" {
			return binary
		}
	}
	
	// 检查 Android 运行时目录
	if config.C != nil {
		dataDir := config.C.Data.Dir
		if dataDir != "" {
			androidPipPaths := []string{
				filepath.Join(dataDir, "deps", "bin", "python", "bin", "pip3"),
				filepath.Join(dataDir, "deps", "bin", "python", "bin", "pip"),
			}
			for _, pipPath := range androidPipPaths {
				if _, err := os.Stat(pipPath); err == nil {
					log.Printf("[ResolveManagedPipBinary] Found Android pip: %s", pipPath)
					return pipPath
				}
			}
		}
	}
	
	return ""
}

func createManagedPythonCommand(scriptPath string, scriptArgs []string, workDir string, envVars map[string]string, runtimePaths managedRuntimePaths) (*exec.Cmd, func(), error) {
	EnsureManagedPythonVenv()
	runtimePaths = currentManagedRuntimePaths()
	preferredDirs := append([]string{runtimePaths.VenvBin}, windowsPythonPreferredDirs...)
	pythonBin, err := resolveManagedBinary("python", preferredDirs, runtimePaths.searchDirs)
	if err != nil {
		pythonBin, err = resolveManagedBinary("python3", preferredDirs, runtimePaths.searchDirs)
		if err != nil {
			return nil, nil, err
		}
	}

	tempDir, envFile, cleanup, err := writeManagedRuntimeEnvFile(envVars)
	if err != nil {
		return nil, nil, err
	}
	_ = tempDir

	args := []string{"-u", "-c", pythonEnvBootstrap, envFile, scriptPath, strings.TrimSpace(envVars["PYTHONPATH"])}
	args = append(args, scriptArgs...)

	cmd := exec.Command(pythonBin, args...)
	cmd.Dir = workDir
	cmd.Env = appendPythonBootstrapEnv(buildBootstrapProcessEnv(envVars))
	setPgid(cmd)
	return cmd, cleanup, nil
}

func createManagedNodeCommand(scriptPath string, scriptArgs []string, workDir string, envVars map[string]string, runtimePaths managedRuntimePaths) (*exec.Cmd, func(), error) {
	nodeBin, err := resolveManagedBinary("node", nil, runtimePaths.searchDirs)
	if err != nil {
		return nil, nil, err
	}

	_, envFile, cleanup, err := writeManagedRuntimeEnvFile(envVars)
	if err != nil {
		return nil, nil, err
	}
	nodeModulesCleanup := ensureManagedNodeModulesAccess(workDir, runtimePaths.NodeModules)

	preloadFile, preloadErr := writeNodePreloadScript(filepath.Dir(envFile), envFile, envVars)
	if preloadErr != nil {
		cleanup()
		nodeModulesCleanup()
		return nil, nil, preloadErr
	}

	args := []string{"--require", preloadFile, scriptPath}
	args = append(args, scriptArgs...)

	cmd := exec.Command(nodeBin, args...)
	cmd.Dir = workDir
	cmd.Env = buildBootstrapProcessEnv(envVars)
	setPgid(cmd)
	return cmd, combineCleanup(cleanup, nodeModulesCleanup), nil
}

func createManagedTSNodeCommand(scriptPath string, scriptArgs []string, workDir string, envVars map[string]string, runtimePaths managedRuntimePaths) (*exec.Cmd, func(), error) {
	_, envFile, cleanup, err := writeManagedRuntimeEnvFile(envVars)
	if err != nil {
		return nil, nil, err
	}
	nodeModulesCleanup := ensureManagedNodeModulesAccess(workDir, runtimePaths.NodeModules)

	preloadFile, preloadErr := writeNodePreloadScript(filepath.Dir(envFile), envFile, envVars)
	if preloadErr != nil {
		cleanup()
		nodeModulesCleanup()
		return nil, nil, preloadErr
	}

	tsNodeBin, tsErr := resolveManagedBinary("ts-node", []string{runtimePaths.NodeBin}, runtimePaths.searchDirs)
	if tsErr == nil {
		args := []string{"--require", preloadFile, scriptPath}
		args = append(args, scriptArgs...)
		cmd := exec.Command(tsNodeBin, args...)
		cmd.Dir = workDir
		cmd.Env = buildBootstrapProcessEnv(envVars)
		setPgid(cmd)
		return cmd, combineCleanup(cleanup, nodeModulesCleanup), nil
	}

	npxBin, err := resolveManagedBinary("npx", nil, runtimePaths.searchDirs)
	if err != nil {
		cleanup()
		nodeModulesCleanup()
		return nil, nil, err
	}

	args := []string{"ts-node", "--require", preloadFile, scriptPath}
	args = append(args, scriptArgs...)

	cmd := exec.Command(npxBin, args...)
	cmd.Dir = workDir
	cmd.Env = buildBootstrapProcessEnv(envVars)
	setPgid(cmd)
	return cmd, combineCleanup(cleanup, nodeModulesCleanup), nil
}

func createStandardManagedCommand(interpreter, scriptPath string, scriptArgs []string, workDir string, envVars map[string]string, runtimePaths managedRuntimePaths) (*exec.Cmd, func(), error) {
	binary, err := resolveManagedBinary(interpreter, standardBinaryPreferredDirs(interpreter, runtimePaths), runtimePaths.searchDirs)
	if err != nil {
		return nil, nil, err
	}

	var args []string
	switch interpreter {
	case "go":
		args = append([]string{"run", scriptPath}, scriptArgs...)
	case "bash":
		args = append([]string{scriptPath}, scriptArgs...)
	default:
		args = append([]string{scriptPath}, scriptArgs...)
	}

	cmd := exec.Command(binary, args...)
	cmd.Dir = workDir
	cmd.Env = buildEnv(envVars)
	setPgid(cmd)
	return cmd, func() {}, nil
}

func standardBinaryPreferredDirs(interpreter string, runtimePaths managedRuntimePaths) []string {
	switch interpreter {
	case "bash":
		if runtime.GOOS == "windows" {
			return windowsShellSearchDirs
		}
		return nil
	case "go":
		return nil
	default:
		return nil
	}
}

type managedBootstrapCommand struct {
	binary string
	args   []string
}

func managedPythonBootstrapCommands() []managedBootstrapCommand {
	commands := []managedBootstrapCommand{
		{binary: "python3", args: []string{"-m", "venv"}},
		{binary: "python", args: []string{"-m", "venv"}},
	}
	if runtime.GOOS == "windows" {
		commands = append(commands, managedBootstrapCommand{binary: "py", args: []string{"-3", "-m", "venv"}})
		commands = append(commands, managedBootstrapCommand{binary: "py", args: []string{"-m", "venv"}})
	}
	return commands
}

func buildBootstrapProcessEnv(envVars map[string]string) []string {
	safeKeys := []string{"PATH", "HOME", "USER", "LANG", "LC_ALL", "TZ"}
	if runtime.GOOS == "windows" {
		safeKeys = append(safeKeys, "SYSTEMROOT", "PATHEXT", "TEMP", "TMP", "APPDATA", "LOCALAPPDATA", "USERPROFILE")
	}

	env := make([]string, 0, len(safeKeys))
	for _, key := range safeKeys {
		value := os.Getenv(key)
		if key == "PATH" && strings.TrimSpace(envVars["PATH"]) != "" {
			value = envVars["PATH"]
		}
		if value == "" {
			continue
		}
		env = append(env, key+"="+value)
	}

	return AppendProxyEnv(env)
}

func appendPythonBootstrapEnv(env []string) []string {
	hasUTF8 := false
	hasEncoding := false
	for _, entry := range env {
		if strings.HasPrefix(entry, "PYTHONUTF8=") {
			hasUTF8 = true
		}
		if strings.HasPrefix(entry, "PYTHONIOENCODING=") {
			hasEncoding = true
		}
	}
	if !hasUTF8 {
		env = append(env, "PYTHONUTF8=1")
	}
	if !hasEncoding {
		env = append(env, "PYTHONIOENCODING=utf-8")
	}
	return env
}

func writeManagedRuntimeEnvFile(envVars map[string]string) (string, string, func(), error) {
	tempDir, err := os.MkdirTemp("", "daidai-runtime-*")
	if err != nil {
		return "", "", nil, err
	}

	cleanup := func() {
		_ = os.RemoveAll(tempDir)
	}

	payload := make(map[string]string, len(envVars))
	for key, value := range envVars {
		if strings.ContainsRune(value, 0) {
			continue
		}
		payload[key] = value
	}

	data, err := json.Marshal(payload)
	if err != nil {
		cleanup()
		return "", "", nil, err
	}

	envFile := filepath.Join(tempDir, "env.json")
	if err := os.WriteFile(envFile, data, 0o600); err != nil {
		cleanup()
		return "", "", nil, err
	}

	return tempDir, envFile, cleanup, nil
}

func writeNodePreloadScript(tempDir, envFile string, envVars map[string]string) (string, error) {
	helperPath := filepath.ToSlash(strings.TrimSpace(envVars["DAIDAI_SEND_NOTIFY_JS"]))
	nodePathList := strings.Split(strings.TrimSpace(envVars["NODE_PATH"]), string(os.PathListSeparator))
	filteredNodePaths := make([]string, 0, len(nodePathList))
	for _, item := range nodePathList {
		item = strings.TrimSpace(item)
		if item != "" {
			filteredNodePaths = append(filteredNodePaths, filepath.ToSlash(item))
		}
	}

	helperJSON, err := json.Marshal(helperPath)
	if err != nil {
		return "", err
	}
	nodePathsJSON, err := json.Marshal(filteredNodePaths)
	if err != nil {
		return "", err
	}

	script := fmt.Sprintf(`const fs = require('fs');
const path = require('path');
const Module = require('module');
const envPayload = JSON.parse(fs.readFileSync(%q, 'utf8'));
for (const [key, value] of Object.entries(envPayload)) {
  if (value === undefined || value === null) {
    continue;
  }
  process.env[key] = String(value);
}
const extraNodePaths = %s;
const mergedNodePaths = [];
for (const value of [...extraNodePaths, ...(process.env.NODE_PATH ? process.env.NODE_PATH.split(path.delimiter) : [])]) {
  if (!value) {
    continue;
  }
  if (!mergedNodePaths.includes(value)) {
    mergedNodePaths.push(value);
  }
}
if (mergedNodePaths.length > 0) {
  process.env.NODE_PATH = mergedNodePaths.join(path.delimiter);
  Module._initPaths();
}
const _origResolve = Module._resolveFilename;
function _resolveExportsEntry(exp) {
  if (typeof exp === 'string') return exp;
  if (exp && typeof exp === 'object') {
    return exp.require || exp.default || exp.node || exp.import || '';
  }
  return '';
}
Module._resolveFilename = function(request, parent, isMain, options) {
  try {
    return _origResolve.call(this, request, parent, isMain, options);
  } catch (err) {
    if (err.code === 'ERR_PACKAGE_PATH_NOT_EXPORTED') {
      const parts = request.split('/');
      const pkgName = parts[0].startsWith('@') ? parts.slice(0, 2).join('/') : parts[0];
      const subPath = parts.slice(pkgName.startsWith('@') ? 2 : 1).join('/');
      for (const np of (process.env.NODE_PATH || '').split(path.delimiter)) {
        if (!np) continue;
        try {
          const pkgDir = path.join(np, pkgName);
          const pkgJson = JSON.parse(fs.readFileSync(path.join(pkgDir, 'package.json'), 'utf8'));
          let target = '';
          if (subPath) {
            const exportKey = './' + subPath;
            if (pkgJson.exports && pkgJson.exports[exportKey]) {
              target = _resolveExportsEntry(pkgJson.exports[exportKey]);
            }
            if (!target) target = subPath;
          } else {
            if (pkgJson.exports && pkgJson.exports['.']) {
              target = _resolveExportsEntry(pkgJson.exports['.']);
            }
            if (!target) target = pkgJson.main || '';
            if (!target) target = 'index.js';
          }
          const candidates = [
            path.join(pkgDir, target),
            path.join(pkgDir, target + '.js'),
            path.join(pkgDir, target, 'index.js')
          ];
          for (const c of candidates) {
            if (fs.existsSync(c)) return c;
          }
        } catch (_) {}
      }
    }
    throw err;
  }
};
const helperPath = %s;
if (helperPath) {
  require(helperPath);
}
`, filepath.ToSlash(envFile), string(nodePathsJSON), string(helperJSON))

	preloadFile := filepath.Join(tempDir, "node-preload.js")
	if err := os.WriteFile(preloadFile, []byte(script), 0o600); err != nil {
		return "", err
	}

	return preloadFile, nil
}

func resolveManagedBinary(name string, preferredDirs []string, fallbackDirs []string) (string, error) {
	if strings.ContainsRune(name, os.PathSeparator) || strings.Contains(name, "/") {
		if isExecutableFile(name) {
			return name, nil
		}
		return "", fmt.Errorf("找不到可执行文件: %s", name)
	}

	searchDirs := make([]string, 0, len(preferredDirs)+len(fallbackDirs))
	seen := make(map[string]struct{}, len(preferredDirs)+len(fallbackDirs))
	for _, dir := range append(preferredDirs, fallbackDirs...) {
		dir = strings.TrimSpace(dir)
		if dir == "" {
			continue
		}
		if _, exists := seen[dir]; exists {
			continue
		}
		seen[dir] = struct{}{}
		searchDirs = append(searchDirs, dir)
	}

	for _, dir := range searchDirs {
		if binary := findExecutableInDir(dir, name); binary != "" {
			return binary, nil
		}
	}

	return "", fmt.Errorf("找不到可执行文件: %s", name)
}

func findExecutableInDir(dir, name string) string {
	if dir == "" {
		return ""
	}

	candidates := []string{name}
	if runtime.GOOS == "windows" && filepath.Ext(name) == "" {
		pathext := os.Getenv("PATHEXT")
		if pathext == "" {
			pathext = ".COM;.EXE;.BAT;.CMD"
		}
		for _, ext := range strings.Split(pathext, ";") {
			ext = strings.TrimSpace(ext)
			if ext == "" {
				continue
			}
			candidates = append(candidates, name+strings.ToLower(ext))
			candidates = append(candidates, name+strings.ToUpper(ext))
		}
	}

	for _, candidate := range candidates {
		fullPath := filepath.Join(dir, candidate)
		if isExecutableFile(fullPath) {
			return fullPath
		}
	}

	return ""
}

func isExecutableFile(path string) bool {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return false
	}
	if runtime.GOOS == "windows" {
		return true
	}
	return info.Mode()&0o111 != 0
}

func findVenvSitePackages(venvDir string) string {
	venvDir = strings.TrimSpace(venvDir)
	if venvDir == "" {
		return ""
	}

	windowsSitePackages := filepath.Join(venvDir, "Lib", "site-packages")
	if info, err := os.Stat(windowsSitePackages); err == nil && info.IsDir() {
		return windowsSitePackages
	}

	venvLib := filepath.Join(venvDir, "lib")
	entries, err := os.ReadDir(venvLib)
	if err != nil {
		return ""
	}
	for _, entry := range entries {
		if entry.IsDir() && strings.HasPrefix(entry.Name(), "python") {
			return filepath.Join(venvLib, entry.Name(), "site-packages")
		}
	}
	return ""
}

func ensureManagedNodeModulesAccess(workDir, nodeModules string) func() {
	workDir = strings.TrimSpace(workDir)
	nodeModules = strings.TrimSpace(nodeModules)
	if workDir == "" || nodeModules == "" {
		return func() {}
	}

	if info, err := os.Stat(nodeModules); err != nil || !info.IsDir() {
		return func() {}
	}

	linkPath := filepath.Join(workDir, "node_modules")
	if _, err := os.Lstat(linkPath); err == nil || !os.IsNotExist(err) {
		return func() {}
	}

	if err := createManagedDirectoryLink(nodeModules, linkPath); err != nil {
		return func() {}
	}

	return func() {
		_ = os.Remove(linkPath)
	}
}

func createManagedDirectoryLink(target, link string) error {
	if runtime.GOOS == "windows" {
		cmd := exec.Command("cmd", "/C", "mklink", "/J", link, target)
		if output, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("create node_modules junction: %w: %s", err, strings.TrimSpace(string(output)))
		}
		return nil
	}

	return os.Symlink(target, link)
}

func combineCleanup(cleanups ...func()) func() {
	return func() {
		for _, cleanup := range cleanups {
			if cleanup != nil {
				cleanup()
			}
		}
	}
}

func sanitizeManagedPath(currentPath, nodeBin, venvBin string) string {
	cleanNodeBin := filepath.Clean(strings.TrimSpace(nodeBin))
	cleanVenvBin := filepath.Clean(strings.TrimSpace(venvBin))

	segments := make([]string, 0)
	seen := make(map[string]struct{})
	for _, item := range splitPathDirs(currentPath) {
		cleanItem := filepath.Clean(strings.TrimSpace(item))
		if cleanItem == "" || cleanItem == "." {
			continue
		}
		if cleanItem == cleanNodeBin || cleanItem == cleanVenvBin {
			continue
		}
		if _, exists := seen[cleanItem]; exists {
			continue
		}
		seen[cleanItem] = struct{}{}
		segments = append(segments, cleanItem)
	}

	return strings.Join(segments, string(os.PathListSeparator))
}

func splitPathDirs(raw string) []string {
	parts := strings.Split(raw, string(os.PathListSeparator))
	result := make([]string, 0, len(parts))
	for _, item := range parts {
		item = strings.TrimSpace(item)
		if item != "" {
			result = append(result, item)
		}
	}
	return result
}

func joinPathSegments(parts ...string) string {
	joined := make([]string, 0, len(parts))
	seen := make(map[string]struct{}, len(parts))
	for _, part := range parts {
		for _, item := range splitPathDirs(part) {
			cleanItem := filepath.Clean(strings.TrimSpace(item))
			if cleanItem == "" || cleanItem == "." {
				continue
			}
			if _, exists := seen[cleanItem]; exists {
				continue
			}
			seen[cleanItem] = struct{}{}
			joined = append(joined, cleanItem)
		}
	}
	return strings.Join(joined, string(os.PathListSeparator))
}

func loadConfigShellVars(envMap map[string]string) {
	dataDir := ""
	if config.C != nil {
		dataDir = config.C.Data.Dir
	}
	if dataDir == "" {
		return
	}

	configPath := filepath.Join(dataDir, "config.sh")
	f, err := os.Open(configPath)
	if err != nil {
		return
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")
		idx := strings.IndexByte(line, '=')
		if idx <= 0 {
			continue
		}
		key := strings.TrimSpace(line[:idx])
		val := strings.TrimSpace(line[idx+1:])
		val = strings.Trim(val, "\"'")
		if key != "" {
			envMap[key] = val
		}
	}
}
