package service

import (
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"daidai-panel/config"
	"daidai-panel/model"
)

type gitAuthConfig struct {
	Env         []string
	RemoteURL   string
	CleanupFunc func()
}

func buildGitAuthConfig(baseEnv []string, remoteURL string, sub *model.Subscription, sshKeyPath string) (gitAuthConfig, error) {
	env := AppendProxyEnv(baseEnv)
	cleanup := func() {}
	remoteURL = strings.TrimSpace(remoteURL)
	authType := ""
	if sub != nil {
		authType = sub.EffectiveAuthType()
	}

	switch authType {
	case model.SubAuthTypeSSH:
		sshKeyPath = strings.TrimSpace(sshKeyPath)
		if sshKeyPath == "" {
			return gitAuthConfig{}, fmt.Errorf("已配置 SSH 鉴权，但未找到可用 SSH 密钥")
		}

		knownHostsPath, err := ensureGitKnownHostsFile()
		if err != nil {
			return gitAuthConfig{}, err
		}
		env = append(env, "GIT_SSH_COMMAND="+buildGitSSHCommand(sshKeyPath, knownHostsPath))
	case model.SubAuthTypeToken:
		if sub == nil || strings.TrimSpace(sub.AuthToken) == "" {
			return gitAuthConfig{}, fmt.Errorf("已配置 Token 鉴权，但访问令牌为空")
		}
		if strings.HasPrefix(strings.ToLower(remoteURL), "ssh://") || strings.Contains(remoteURL, "@") && strings.Contains(remoteURL, ":") && !strings.Contains(remoteURL, "://") {
			return gitAuthConfig{}, fmt.Errorf("Token 鉴权仅支持 HTTP/HTTPS 仓库地址，请改用 HTTPS 地址")
		}
		headerValue := "Authorization: Basic " + buildGitBasicAuthValue(sub.AuthToken)
		env = append(env, "GIT_HTTP_EXTRA_HEADER="+headerValue)
	}

	return gitAuthConfig{
		Env:         env,
		RemoteURL:   remoteURL,
		CleanupFunc: cleanup,
	}, nil
}

func ensureGitKnownHostsFile() (string, error) {
	if config.C == nil {
		return "", fmt.Errorf("配置未初始化")
	}

	sshDir := filepath.Join(config.C.Data.Dir, "ssh")
	if err := os.MkdirAll(sshDir, 0o700); err != nil {
		return "", fmt.Errorf("创建 SSH 配置目录失败: %w", err)
	}

	knownHostsPath := filepath.Join(sshDir, "known_hosts")
	if _, err := os.Stat(knownHostsPath); os.IsNotExist(err) {
		if err := os.WriteFile(knownHostsPath, []byte{}, 0o600); err != nil {
			return "", fmt.Errorf("创建 known_hosts 失败: %w", err)
		}
	} else if err != nil {
		return "", fmt.Errorf("读取 known_hosts 失败: %w", err)
	}

	return knownHostsPath, nil
}

func buildGitSSHCommand(sshKeyPath, knownHostsPath string) string {
	return fmt.Sprintf(
		"ssh -i %s -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=%s",
		shellEscapeSSHArg(sshKeyPath),
		shellEscapeSSHArg(knownHostsPath),
	)
}

func shellEscapeSSHArg(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\\''") + "'"
}

func buildGitBasicAuthValue(token string) string {
	encoded := base64.StdEncoding.EncodeToString([]byte("x-access-token:" + strings.TrimSpace(token)))
	return encoded
}

func buildGitHTTPAuthHeader(token string) string {
	return "Authorization: Basic " + buildGitBasicAuthValue(token)
}
