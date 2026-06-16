// desktop_organizer.rs - Органайзер рабочего стола на Rust (CLI)
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::io::{self, Write};
use std::time::SystemTime;
use walkdir::WalkDir;
use colored::*;

#[derive(Serialize, Deserialize, Clone)]
struct Config {
    categories: HashMap<String, Vec<String>>,
    excluded_folders: Vec<String>,
    excluded_files: Vec<String>,
    auto_sort_on_start: bool,
}

#[derive(Serialize, Deserialize)]
struct HistoryEntry {
    from: String,
    to: String,
}

const CONFIG_FILE: &str = "config.json";
const HISTORY_FILE: &str = "history.json";
const LOG_FILE: &str = "organizer.log";

fn default_config() -> Config {
    let mut categories = HashMap::new();
    categories.insert("Images".to_string(), vec![".jpg".into(), ".jpeg".into(), ".png".into(), ".gif".into(), ".bmp".into(), ".svg".into(), ".webp".into()]);
    categories.insert("Documents".to_string(), vec![".pdf".into(), ".doc".into(), ".docx".into(), ".txt".into(), ".md".into(), ".xls".into(), ".xlsx".into(), ".ppt".into(), ".pptx".into(), ".odt".into(), ".ods".into()]);
    categories.insert("Archives".to_string(), vec![".zip".into(), ".rar".into(), ".7z".into(), ".tar".into(), ".gz".into(), ".bz2".into(), ".xz".into()]);
    categories.insert("Music".to_string(), vec![".mp3".into(), ".wav".into(), ".flac".into(), ".aac".into(), ".ogg".into(), ".m4a".into()]);
    categories.insert("Video".to_string(), vec![".mp4".into(), ".avi".into(), ".mkv".into(), ".mov".into(), ".wmv".into(), ".flv".into(), ".webm".into()]);
    categories.insert("Programs".to_string(), vec![".exe".into(), ".msi".into(), ".dmg".into(), ".appimage".into(), ".deb".into(), ".rpm".into(), ".sh".into(), ".bat".into()]);
    categories.insert("Others".to_string(), vec![]);
    Config {
        categories,
        excluded_folders: vec!["Desktop Organizer".into(), "System Volume Information".into()],
        excluded_files: vec!["desktop.ini".into(), ".DS_Store".into(), "Thumbs.db".into()],
        auto_sort_on_start: true,
    }
}

fn load_config() -> Config {
    if let Ok(data) = fs::read_to_string(CONFIG_FILE) {
        if let Ok(cfg) = serde_json::from_str(&data) {
            return cfg;
        }
    }
    let cfg = default_config();
    let data = serde_json::to_string_pretty(&cfg).unwrap();
    fs::write(CONFIG_FILE, data).unwrap();
    cfg
}

fn load_history() -> Vec<HistoryEntry> {
    if let Ok(data) = fs::read_to_string(HISTORY_FILE) {
        if let Ok(h) = serde_json::from_str(&data) {
            return h;
        }
    }
    Vec::new()
}

fn save_history(history: &[HistoryEntry]) {
    let data = serde_json::to_string_pretty(history).unwrap();
    fs::write(HISTORY_FILE, data).unwrap();
}

fn log_message(msg: &str) {
    let now = chrono::Local::now().format("%Y-%m-%d %H:%M:%S");
    let line = format!("[{}] {}\n", now, msg);
    let _ = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(LOG_FILE)
        .and_then(|mut f| f.write_all(line.as_bytes()));
    println!("{}", msg);
}

fn get_category(filename: &Path, config: &Config) -> String {
    let ext = filename.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    for (cat, exts) in &config.categories {
        if exts.contains(&ext) {
            return cat.clone();
        }
    }
    "Others".to_string()
}

fn organize(folder: &Path, dry_run: bool, config: &Config) -> Result<Vec<HistoryEntry>, Box<dyn std::error::Error>> {
    if !folder.exists() {
        log_message(&format!("Папка {} не существует", folder.display()));
        return Ok(vec![]);
    }
    let mut history = Vec::new();
    let excluded_folders: std::collections::HashSet<String> = config.excluded_folders.iter().cloned().collect();
    let excluded_files: std::collections::HashSet<String> = config.excluded_files.iter().cloned().collect();

    // Создаём папки категорий
    let mut cat_dirs = HashMap::new();
    for cat in config.categories.keys() {
        let dir = folder.join(cat);
        if !dir.exists() && !dry_run {
            fs::create_dir_all(&dir)?;
        }
        cat_dirs.insert(cat.clone(), dir);
    }

    let mut moved = 0;
    for entry in WalkDir::new(folder).max_depth(1).into_iter().filter_map(|e| e.ok()) {
        if entry.path() == folder {
            continue;
        }
        if entry.file_type().is_dir() {
            let name = entry.file_name().to_string_lossy().to_string();
            if excluded_folders.contains(&name) {
                continue;
            }
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        if excluded_files.contains(&name) {
            continue;
        }
        let cat = get_category(entry.path(), config);
        let dest_dir = cat_dirs.get(&cat).unwrap_or(&folder.join("Others"));
        if cat == "Others" && !dest_dir.exists() && !dry_run {
            fs::create_dir_all(dest_dir)?;
        }
        let dest_path = dest_dir.join(entry.file_name());
        // если существует, добавить счётчик
        let mut dest_path_final = dest_path.clone();
        if dest_path.exists() {
            let stem = dest_path.file_stem().unwrap().to_string_lossy();
            let ext = dest_path.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
            let mut counter = 1;
            while let Some(path) = dest_dir.join(format!("{}_{}{}", stem, counter, ext)).clone().to_str() {
                let candidate = PathBuf::from(path);
                if !candidate.exists() {
                    dest_path_final = candidate;
                    break;
                }
                counter += 1;
            }
        }
        if !dry_run {
            fs::rename(entry.path(), &dest_path_final)?;
            history.push(HistoryEntry {
                from: dest_path_final.to_string_lossy().into_owned(),
                to: entry.path().to_string_lossy().into_owned(),
            });
            log_message(&format!("Перемещён {} -> {}/", name, cat));
        }
        moved += 1;
    }
    log_message(&format!("Обработано {} файлов", moved));
    Ok(history)
}

fn undo(history: &mut Vec<HistoryEntry>, count: usize) -> Result<usize, Box<dyn std::error::Error>> {
    let mut undone = 0;
    for _ in 0..count {
        if let Some(entry) = history.pop() {
            let from = PathBuf::from(&entry.from);
            if from.exists() {
                fs::rename(&from, PathBuf::from(&entry.to))?;
                log_message(&format!("Отмена: {} возвращён", from.file_name().unwrap().to_string_lossy()));
                undone += 1;
            } else {
                log_message(&format!("Файл {} уже не существует", entry.from));
            }
        }
    }
    if undone > 0 {
        save_history(history);
    }
    Ok(undone)
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    let mut target = dirs::home_dir().unwrap().join("Desktop");
    let mut dry_run = false;
    let mut undo_count = 0;
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--path" => {
                if i + 1 < args.len() {
                    target = PathBuf::from(&args[i+1]);
                    i += 2;
                } else {
                    eprintln!("Не указан путь");
                    return Ok(());
                }
            }
            "--dry-run" => { dry_run = true; i += 1; }
            "--undo" => {
                if i + 1 < args.len() {
                    undo_count = args[i+1].parse().unwrap_or(0);
                    i += 2;
                } else {
                    i += 1;
                }
            }
            _ => { i += 1; }
        }
    }

    let config = load_config();
    let mut history = load_history();

    if undo_count > 0 {
        let undone = undo(&mut history, undo_count)?;
        println!("Отменено {} действий", undone);
    } else {
        let new_history = organize(&target, dry_run, &config)?;
        if !dry_run && !new_history.is_empty() {
            history.extend(new_history);
            save_history(&history);
        }
    }
    Ok(())
}
