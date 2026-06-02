// Package mobile provides mobile platform bindings for daidai-panel.
// This package exports functions that can be called from Android (via gomobile)
// and iOS (via gomobile bind) to manage the panel server lifecycle.
package mobile

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
)

// DaidaiPanel is the main entry point for mobile platforms
type DaidaiPanel struct {
	server     *MobileServer
	syncMgr    *SyncManager
}

// NewDaidaiPanel creates a new DaidaiPanel instance
func NewDaidaiPanel() *DaidaiPanel {
	return &DaidaiPanel{
		server: GetServer(),
	}
}

// StartServer starts the panel server
// dataDir: directory for storing panel data
// webDir: directory containing frontend static files
// port: port to listen on (0 for default 5701)
// Returns JSON string with server state
func (p *DaidaiPanel) StartServer(dataDir, webDir string, port int) string {
	if err := p.server.Start(dataDir, webDir, port); err != nil {
		return p.errorResponse(err.Error())
	}
	return p.successResponse()
}

// StopServer stops the panel server
// Returns JSON string with result
func (p *DaidaiPanel) StopServer() string {
	if err := p.server.Stop(); err != nil {
		return p.errorResponse(err.Error())
	}
	return p.successResponse()
}

// GetServerState returns the current server state as JSON
func (p *DaidaiPanel) GetServerState() string {
	state := p.server.GetState()
	data, err := json.Marshal(state)
	if err != nil {
		return p.errorResponse(err.Error())
	}
	return string(data)
}

// IsServerRunning returns whether the server is running
func (p *DaidaiPanel) IsServerRunning() bool {
	return p.server.IsRunning()
}

// GetServerPort returns the current server port
func (p *DaidaiPanel) GetServerPort() int {
	return p.server.GetPort()
}

// GetServerURL returns the current server URL
func (p *DaidaiPanel) GetServerURL() string {
	state := p.server.GetState()
	return state.URL
}

// InitDataDir initializes the data directory with default structure
func (p *DaidaiPanel) InitDataDir(dataDir string) string {
	dirs := []string{
		filepath.Join(dataDir, "scripts"),
		filepath.Join(dataDir, "logs"),
		filepath.Join(dataDir, "backups"),
		filepath.Join(dataDir, "deps"),
	}

	for _, dir := range dirs {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return p.errorResponse(fmt.Sprintf("failed to create directory %s: %v", dir, err))
		}
	}

	return p.successResponse()
}

// InitWebDir initializes the web directory by extracting embedded resources
// webDir: target directory for frontend files
// assetsData: byte array of the zipped frontend assets
// Returns JSON string with result
func (p *DaidaiPanel) InitWebDir(webDir string, assetsData []byte) string {
	// TODO: Implement zip extraction
	// For now, just ensure the directory exists
	if err := os.MkdirAll(webDir, 0755); err != nil {
		return p.errorResponse(fmt.Sprintf("failed to create web directory: %v", err))
	}

	// Check if index.html exists
	indexPath := filepath.Join(webDir, "index.html")
	if _, err := os.Stat(indexPath); err != nil {
		return p.errorResponse("web directory does not contain index.html")
	}

	return p.successResponse()
}

// GetVersion returns the panel version
func (p *DaidaiPanel) GetVersion() string {
	return "2.2.14-mobile"
}

// LogMessage logs a message to the panel log
func (p *DaidaiPanel) LogMessage(tag, message string) {
	log.Printf("[%s] %s", tag, message)
}

// SyncExportData exports all syncable data as JSON
func (p *DaidaiPanel) SyncExportData(dataDir string) string {
	if p.syncMgr == nil {
		p.syncMgr = NewSyncManager(dataDir)
	}

	jsonData, err := p.syncMgr.ExportToJSON()
	if err != nil {
		return p.errorResponse(fmt.Sprintf("export failed: %v", err))
	}

	return jsonData
}

// SyncImportData imports syncable data from JSON
func (p *DaidaiPanel) SyncImportData(dataDir, jsonData string) string {
	if p.syncMgr == nil {
		p.syncMgr = NewSyncManager(dataDir)
	}

	if err := p.syncMgr.ImportFromJSON(jsonData); err != nil {
		return p.errorResponse(fmt.Sprintf("import failed: %v", err))
	}

	return p.successResponse()
}

// SyncExportToFile exports sync data to a file
func (p *DaidaiPanel) SyncExportToFile(dataDir, filePath string) string {
	if p.syncMgr == nil {
		p.syncMgr = NewSyncManager(dataDir)
	}

	if err := p.syncMgr.ExportToFile(filePath); err != nil {
		return p.errorResponse(fmt.Sprintf("export to file failed: %v", err))
	}

	return p.successResponse()
}

// SyncImportFromFile imports sync data from a file
func (p *DaidaiPanel) SyncImportFromFile(dataDir, filePath string) string {
	if p.syncMgr == nil {
		p.syncMgr = NewSyncManager(dataDir)
	}

	if err := p.syncMgr.ImportFromFile(filePath); err != nil {
		return p.errorResponse(fmt.Sprintf("import from file failed: %v", err))
	}

	return p.successResponse()
}

// SyncGetStatus returns the current sync status as JSON
func (p *DaidaiPanel) SyncGetStatus(dataDir string) string {
	if p.syncMgr == nil {
		p.syncMgr = NewSyncManager(dataDir)
	}

	status, err := p.syncMgr.GetSyncStatus()
	if err != nil {
		return p.errorResponse(fmt.Sprintf("get sync status failed: %v", err))
	}

	data, err := json.Marshal(status)
	if err != nil {
		return p.errorResponse(err.Error())
	}

	return string(data)
}

func (p *DaidaiPanel) successResponse() string {
	return `{"success":true}`
}

func (p *DaidaiPanel) errorResponse(message string) string {
	return fmt.Sprintf(`{"success":false,"error":"%s"}`, message)
}
