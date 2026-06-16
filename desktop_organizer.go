// desktop_organizer.go - Органайзер рабочего стола на Go (CLI)
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io/ioutil"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type Config struct {
	Categories      map[string][]string `json:"categories"`
	ExcludedFolders []string            `json:"excluded_folders"`
	ExcludedFiles   []string            `json:"excluded_files"`
	AutoSortOnStart bool                `json:"auto_sort_on_start"`
}

type HistoryEntry struct {
	From string `json:"from"`
	To   string `json:"to"`
}

var (
	configPath string
	targetPath string
	dryRun     bool
	undoCount  int
)

func main() {
	flag.StringVar(&configPath, "config", "config.json", "путь к файлу конфигурации")
	flag.StringVar(&targetPath, "path", defaultDesktop(), "папка для сортировки")
	flag.BoolVar(&dryRun, "dry-run", false, "только показать действия")
	flag.IntVar(&undoCount, "undo", 0, "отменить N действий")
	flag.Parse()

	org := NewOrganizer(configPath)
	if undoCount > 0 {
		org.Undo(undoCount)
	} else {
		org.Organize(targetPath, dryRun)
	}
}

func defaultDesktop() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Desktop")
}

type Organizer struct {
	Config  *Config
	History []HistoryEntry
	LogFile *log.Logger
}

func NewOrganizer(configPath string) *Organizer {
	cfg := loadConfig(configPath)
	history := loadHistory()
	logFile := initLog()
	return &Organizer{Config: cfg, History: history, LogFile: logFile}
}

func loadConfig(path string) *Config {
	cfg := &Config{
		Categories: map[string][]string{
			"Images":    {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".webp"},
			"Documents": {".pdf", ".doc", ".docx", ".txt", ".md", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods"},
			"Archives":  {".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz"},
			"Music":     {".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a"},
			"Video":     {".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm"},
			"Programs":  {".exe", ".msi", ".dmg", ".appimage", ".deb", ".rpm", ".sh", ".bat"},
			"Others":    {},
		},
		ExcludedFolders: []string{"Desktop Organizer", "System Volume Information"},
		ExcludedFiles:   []string{"desktop.ini", ".DS_Store", "Thumbs.db"},
		AutoSortOnStart: true,
	}
	if data, err := ioutil.ReadFile(path); err == nil {
		var loaded Config
		if err := json.Unmarshal(data, &loaded); err == nil {
			cfg = &loaded
		}
	}
	// сохранить дефолтный, если файла нет
	if _, err := os.Stat(path); os.IsNotExist(err) {
		data, _ := json.MarshalIndent(cfg, "", "  ")
		ioutil.WriteFile(path, data, 0644)
	}
	return cfg
}

func loadHistory() []HistoryEntry {
	if data, err := ioutil.ReadFile("history.json"); err == nil {
		var h []HistoryEntry
		json.Unmarshal(data, &h)
		return h
	}
	return []HistoryEntry{}
}

func saveHistory(h []HistoryEntry) {
	data, _ := json.MarshalIndent(h, "", "  ")
	ioutil.WriteFile("history.json", data, 0644)
}

func initLog() *log.Logger {
	file, err := os.OpenFile("organizer.log", os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		log.Fatal(err)
	}
	return log.New(file, "", log.Ldate|log.Ltime)
}

func (o *Organizer) getCategory(filename string) string {
	ext := strings.ToLower(filepath.Ext(filename))
	for cat, exts := range o.Config.Categories {
		for _, e := range exts {
			if e == ext {
				return cat
			}
		}
	}
	return "Others"
}

func (o *Organizer) Organize(folder string, dryRun bool) int {
	info, err := os.Stat(folder)
	if err != nil {
		o.LogFile.Printf("Папка %s не существует", folder)
		return 0
	}
	if !info.IsDir() {
		o.LogFile.Printf("%s не является папкой", folder)
		return 0
	}
	o.History = []HistoryEntry{}
	excludedFolders := make(map[string]bool)
	for _, f := range o.Config.ExcludedFolders {
		excludedFolders[f] = true
	}
	excludedFiles := make(map[string]bool)
	for _, f := range o.Config.ExcludedFiles {
		excludedFiles[f] = true
	}
	// Создаём папки категорий
	catDirs := make(map[string]string)
	for cat := range o.Config.Categories {
		dir := filepath.Join(folder, cat)
		if _, err := os.Stat(dir); os.IsNotExist(err) && !dryRun {
			os.MkdirAll(dir, 0755)
		}
		catDirs[cat] = dir
	}

	entries, _ := ioutil.ReadDir(folder)
	moved := 0
	for _, entry := range entries {
		if entry.IsDir() {
			if excludedFolders[entry.Name()] {
				continue
			}
			continue
		}
		if excludedFiles[entry.Name()] {
			continue
		}
		cat := o.getCategory(entry.Name())
		destDir := catDirs[cat]
		if cat == "Others" {
			if _, err := os.Stat(destDir); os.IsNotExist(err) && !dryRun {
				os.MkdirAll(destDir, 0755)
			}
		}
		src := filepath.Join(folder, entry.Name())
		dest := filepath.Join(destDir, entry.Name())
		// если существует, добавить счётчик
		if _, err := os.Stat(dest); err == nil {
			base := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
			ext := filepath.Ext(entry.Name())
			counter := 1
			for {
				newName := fmt.Sprintf("%s_%d%s", base, counter, ext)
				newDest := filepath.Join(destDir, newName)
				if _, err := os.Stat(newDest); os.IsNotExist(err) {
					dest = newDest
					break
				}
				counter++
			}
		}
		if !dryRun {
			err := os.Rename(src, dest)
			if err != nil {
				o.LogFile.Printf("Ошибка перемещения %s: %v", entry.Name(), err)
				continue
			}
			o.History = append(o.History, HistoryEntry{From: dest, To: src})
			o.LogFile.Printf("Перемещён %s -> %s/", entry.Name(), cat)
		}
		moved++
	}
	if !dryRun {
		saveHistory(o.History)
	}
	o.LogFile.Printf("Обработано %d файлов", moved)
	return moved
}

func (o *Organizer) Undo(count int) int {
	undone := 0
	for i := 0; i < count && len(o.History) > 0; i++ {
		entry := o.History[len(o.History)-1]
		o.History = o.History[:len(o.History)-1]
		if _, err := os.Stat(entry.From); err == nil {
			err := os.Rename(entry.From, entry.To)
			if err == nil {
				o.LogFile.Printf("Отмена: %s возвращён", filepath.Base(entry.From))
				undone++
			} else {
				o.LogFile.Printf("Ошибка отмены: %v", err)
			}
		} else {
			o.LogFile.Printf("Файл %s уже не существует", entry.From)
		}
	}
	if undone > 0 {
		saveHistory(o.History)
	}
	return undone
}
