package mobile

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"
)

// SyncData represents the data structure for synchronization
type SyncData struct {
	Version   string        `json:"version"`
	Timestamp int64         `json:"timestamp"`
	DeviceID  string        `json:"device_id"`
	Platform  string        `json:"platform"`
	Data      SyncDataItems `json:"data"`
}

// SyncDataItems contains all syncable data
type SyncDataItems struct {
	Tasks          []json.RawMessage `json:"tasks,omitempty"`
	EnvVars        []json.RawMessage `json:"env_vars,omitempty"`
	Subscriptions  []json.RawMessage `json:"subscriptions,omitempty"`
	NotifyChannels []json.RawMessage `json:"notify_channels,omitempty"`
	TaskViews      []json.RawMessage `json:"task_views,omitempty"`
}

// SyncStatus represents the current sync status
type SyncStatus struct {
	LastSyncTime   int64  `json:"last_sync_time"`
	LastSyncDevice string `json:"last_sync_device"`
	HasConflict    bool   `json:"has_conflict"`
	ConflictCount  int    `json:"conflict_count"`
}

// SyncManager handles data synchronization
type SyncManager struct {
	dataDir  string
	deviceID string
}

// NewSyncManager creates a new SyncManager instance
func NewSyncManager(dataDir string) *SyncManager {
	return &SyncManager{
		dataDir:  dataDir,
		deviceID: getOrCreateDeviceID(dataDir),
	}
}

// ExportData exports all syncable data from the database
func (sm *SyncManager) ExportData() (*SyncData, error) {
	// Read database
	dbPath := filepath.Join(sm.dataDir, "daidai.db")
	if _, err := os.Stat(dbPath); os.IsNotExist(err) {
		return nil, fmt.Errorf("database not found: %s", dbPath)
	}

	// Export data
	data := &SyncData{
		Version:   "1.0",
		Timestamp: time.Now().Unix(),
		DeviceID:  sm.deviceID,
		Platform:  getPlatform(),
	}

	// TODO: Read actual data from SQLite database
	// For now, return empty data structure
	data.Data = SyncDataItems{}

	return data, nil
}

// ImportData imports syncable data into the database
func (sm *SyncManager) ImportData(syncData *SyncData) error {
	if syncData == nil {
		return fmt.Errorf("sync data is nil")
	}

	// Check version compatibility
	if syncData.Version != "1.0" {
		return fmt.Errorf("unsupported sync data version: %s", syncData.Version)
	}

	// Check for conflicts
	conflicts := sm.detectConflicts(syncData)
	if len(conflicts) > 0 {
		// Handle conflicts
		return sm.resolveConflicts(syncData, conflicts)
	}

	// Import data
	return sm.applySyncData(syncData)
}

// ExportToFile exports sync data to a file
func (sm *SyncManager) ExportToFile(filePath string) error {
	data, err := sm.ExportData()
	if err != nil {
		return err
	}

	jsonData, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return err
	}

	// Compress data
	compressed, err := compressData(jsonData)
	if err != nil {
		// If compression fails, write uncompressed
		return os.WriteFile(filePath, jsonData, 0644)
	}

	return os.WriteFile(filePath, compressed, 0644)
}

// ImportFromFile imports sync data from a file
func (sm *SyncManager) ImportFromFile(filePath string) error {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return err
	}

	// Try to decompress
	decompressed, err := decompressData(data)
	if err != nil {
		// If decompression fails, assume uncompressed
		decompressed = data
	}

	var syncData SyncData
	if err := json.Unmarshal(decompressed, &syncData); err != nil {
		return err
	}

	return sm.ImportData(&syncData)
}

// ExportToJSON exports sync data as JSON string
func (sm *SyncManager) ExportToJSON() (string, error) {
	data, err := sm.ExportData()
	if err != nil {
		return "", err
	}

	jsonData, err := json.Marshal(data)
	if err != nil {
		return "", err
	}

	return string(jsonData), nil
}

// ImportFromJSON imports sync data from JSON string
func (sm *SyncManager) ImportFromJSON(jsonStr string) error {
	var syncData SyncData
	if err := json.Unmarshal([]byte(jsonStr), &syncData); err != nil {
		return err
	}

	return sm.ImportData(&syncData)
}

// GetSyncStatus returns the current sync status
func (sm *SyncManager) GetSyncStatus() (*SyncStatus, error) {
	statusFile := filepath.Join(sm.dataDir, ".sync_status")
	if _, err := os.Stat(statusFile); os.IsNotExist(err) {
		return &SyncStatus{
			LastSyncTime: 0,
		}, nil
	}

	data, err := os.ReadFile(statusFile)
	if err != nil {
		return nil, err
	}

	var status SyncStatus
	if err := json.Unmarshal(data, &status); err != nil {
		return nil, err
	}

	return &status, nil
}

// SaveSyncStatus saves the sync status
func (sm *SyncManager) SaveSyncStatus(status *SyncStatus) error {
	statusFile := filepath.Join(sm.dataDir, ".sync_status")
	data, err := json.Marshal(status)
	if err != nil {
		return err
	}

	return os.WriteFile(statusFile, data, 0644)
}

// detectConflicts detects conflicts between local and remote data
func (sm *SyncManager) detectConflicts(remoteData *SyncData) []string {
	var conflicts []string

	// TODO: Implement actual conflict detection
	// Compare timestamps, versions, etc.

	return conflicts
}

// resolveConflicts resolves conflicts between local and remote data
func (sm *SyncManager) resolveConflicts(syncData *SyncData, conflicts []string) error {
	// Default resolution: remote wins
	// TODO: Implement user-selectable conflict resolution
	return sm.applySyncData(syncData)
}

// applySyncData applies sync data to the database
func (sm *SyncManager) applySyncData(syncData *SyncData) error {
	// TODO: Implement actual data application
	// Write to SQLite database

	// Update sync status
	status := &SyncStatus{
		LastSyncTime:   time.Now().Unix(),
		LastSyncDevice: syncData.DeviceID,
		HasConflict:    false,
		ConflictCount:  0,
	}

	return sm.SaveSyncStatus(status)
}

// getOrCreateDeviceID gets or creates a unique device ID
func getOrCreateDeviceID(dataDir string) string {
	idFile := filepath.Join(dataDir, ".device_id")
	if data, err := os.ReadFile(idFile); err == nil {
		return string(data)
	}

	// Generate new device ID
	id := fmt.Sprintf("device-%d", time.Now().UnixNano())
	os.MkdirAll(dataDir, 0755)
	os.WriteFile(idFile, []byte(id), 0644)

	return id
}

// getPlatform returns the current platform
func getPlatform() string {
	// This will be overridden by platform-specific implementations
	return "unknown"
}

// compressData compresses data using gzip
func compressData(data []byte) ([]byte, error) {
	var buf bytes.Buffer
	writer := gzip.NewWriter(&buf)

	if _, err := writer.Write(data); err != nil {
		return nil, err
	}

	if err := writer.Close(); err != nil {
		return nil, err
	}

	return buf.Bytes(), nil
}

// decompressData decompresses gzip data
func decompressData(data []byte) ([]byte, error) {
	reader, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	defer reader.Close()

	return io.ReadAll(reader)
}
