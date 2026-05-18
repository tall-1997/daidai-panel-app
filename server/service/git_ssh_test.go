package service

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"daidai-panel/config"
	"daidai-panel/model"
	"daidai-panel/testutil"
)

func TestAppendGitSSHEnvUsesPersistentKnownHosts(t *testing.T) {
	testutil.SetupTestEnv(t)

	sshKeyPath := filepath.Join(t.TempDir(), "deploy key")
	if err := os.WriteFile(sshKeyPath, []byte("dummy"), 0o600); err != nil {
		t.Fatalf("write ssh key: %v", err)
	}

	sub := &model.Subscription{
		AuthType: model.SubAuthTypeSSH,
	}
	envCfg, err := buildGitAuthConfig([]string{"BASE=1"}, "git@github.com:demo/private.git", sub, sshKeyPath)
	if err != nil {
		t.Fatalf("build git auth config: %v", err)
	}
	env := envCfg.Env

	var sshCommand string
	for _, entry := range env {
		if strings.HasPrefix(entry, "GIT_SSH_COMMAND=") {
			sshCommand = strings.TrimPrefix(entry, "GIT_SSH_COMMAND=")
			break
		}
	}
	if sshCommand == "" {
		t.Fatalf("expected GIT_SSH_COMMAND to be set, env=%v", env)
	}

	if strings.Contains(sshCommand, "StrictHostKeyChecking=no") {
		t.Fatalf("expected host key checking to stay enabled, got %q", sshCommand)
	}
	if strings.Contains(sshCommand, "/dev/null") {
		t.Fatalf("expected persistent known_hosts instead of /dev/null, got %q", sshCommand)
	}
	if !strings.Contains(sshCommand, "StrictHostKeyChecking=accept-new") {
		t.Fatalf("expected accept-new host key policy, got %q", sshCommand)
	}

	knownHostsPath := filepath.Join(config.C.Data.Dir, "ssh", "known_hosts")
	if _, err := os.Stat(knownHostsPath); err != nil {
		t.Fatalf("expected known_hosts file to exist: %v", err)
	}
	if !strings.Contains(sshCommand, shellEscapeSSHArg(knownHostsPath)) {
		t.Fatalf("expected ssh command to reference known_hosts %q, got %q", knownHostsPath, sshCommand)
	}
}

func TestBuildGitAuthConfigUsesHTTPHeaderForTokenAuth(t *testing.T) {
	testutil.SetupTestEnv(t)

	sub := &model.Subscription{
		URL:      "https://github.com/example/private.git",
		AuthType: model.SubAuthTypeToken,
		AuthToken: "ghp_test_token",
	}
	cfg, err := buildGitAuthConfig([]string{"BASE=1"}, sub.URL, sub, "")
	if err != nil {
		t.Fatalf("build git auth config with token: %v", err)
	}

	if cfg.RemoteURL != sub.URL {
		t.Fatalf("expected remote URL unchanged, got %q", cfg.RemoteURL)
	}

	var header string
	for _, entry := range cfg.Env {
		if strings.HasPrefix(entry, "GIT_HTTP_EXTRA_HEADER=") {
			header = strings.TrimPrefix(entry, "GIT_HTTP_EXTRA_HEADER=")
			break
		}
	}
	if header == "" {
		t.Fatalf("expected GIT_HTTP_EXTRA_HEADER to be set, env=%v", cfg.Env)
	}
	if header != buildGitHTTPAuthHeader(sub.AuthToken) {
		t.Fatalf("unexpected auth header: got %q want %q", header, buildGitHTTPAuthHeader(sub.AuthToken))
	}
}

func TestBuildGitAuthConfigRejectsTokenForSSHRemote(t *testing.T) {
	testutil.SetupTestEnv(t)

	sub := &model.Subscription{
		URL:       "git@github.com:example/private.git",
		AuthType:  model.SubAuthTypeToken,
		AuthToken: "ghp_test_token",
	}
	if _, err := buildGitAuthConfig([]string{"BASE=1"}, sub.URL, sub, ""); err == nil {
		t.Fatal("expected token auth to reject SSH remote URL")
	}
}
