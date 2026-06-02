//go:build mobile
// +build mobile

// Package mobile provides gomobile bindings for daidai-panel mobile apps.
// This file contains the gomobile-compatible exported API.
package mobile

import (
	"fmt"
)

// DaidaiPanel is the gomobile-exported class for mobile platforms.
// Android: DaidaiPanel panel = new DaidaiPanel();
// iOS: let panel = DaidaiPanel()
type DaidaiPanel struct {
	manager *PanelManager
}

// NewDaidaiPanel creates a new DaidaiPanel instance.
func NewDaidaiPanel() *DaidaiPanel {
	return &DaidaiPanel{
		manager: GetInstance(),
	}
}

// Initialize sets up the mobile environment.
// dataDir: path to app data directory
// webDir: path to web assets
func (dp *DaidaiPanel) Initialize(dataDir string, webDir string) error {
	return dp.manager.Initialize(dataDir, webDir)
}

// Start starts the panel server.
func (dp *DaidaiPanel) Start() error {
	return dp.manager.StartServer()
}

// Stop stops the panel server.
func (dp *DaidaiPanel) Stop() error {
	return dp.manager.StopServer()
}

// IsRunning returns whether the server is running.
func (dp *DaidaiPanel) IsRunning() bool {
	return dp.manager.IsRunning()
}

// GetURL returns the server URL.
func (dp *DaidaiPanel) GetURL() string {
	return dp.manager.GetServerURL()
}

// GetPort returns the server port.
func (dp *DaidaiPanel) GetPort() int {
	return dp.manager.GetPort()
}

// SetPort sets the server port.
func (dp *DaidaiPanel) SetPort(port int) {
	dp.manager.SetPort(port)
}

// SetAutoStart enables/disables auto-start.
func (dp *DaidaiPanel) SetAutoStart(enabled bool) {
	dp.manager.SetAutoStart(enabled)
}

// IsAutoStart returns whether auto-start is enabled.
func (dp *DaidaiPanel) IsAutoStart() bool {
	return dp.manager.IsAutoStart()
}

// GetUptime returns the server uptime in seconds.
func (dp *DaidaiPanel) GetUptime() int64 {
	return dp.manager.GetUptime()
}

// GetStatus returns a JSON string with server status.
func (dp *DaidaiPanel) GetStatus() string {
	return dp.manager.GetStatus()
}

// ExportData exports the database data as JSON string.
func (dp *DaidaiPanel) ExportData() (string, error) {
	return dp.manager.ExportData()
}

// ImportData imports data from a JSON string.
func (dp *DaidaiPanel) ImportData(jsonData string) error {
	return dp.manager.ImportData(jsonData)
}

// SyncData syncs data with remote server.
func (dp *DaidaiPanel) SyncData(serverURL string, apiKey string) error {
	return dp.manager.SyncData(serverURL, apiKey)
}

// Cleanup releases resources.
func (dp *DaidaiPanel) Cleanup() {
	dp.manager.Cleanup()
}

// String returns a string representation.
func (dp *DaidaiPanel) String() string {
	return fmt.Sprintf("DaidaiPanel{url=%s, running=%v}", dp.GetURL(), dp.IsRunning())
}
