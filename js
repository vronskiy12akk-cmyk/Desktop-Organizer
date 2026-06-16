#!/usr/bin/env node
/**
 * desktop_organizer.js - Органайзер рабочего стола на Node.js (CLI)
 * Использует fs-extra, path и commander для разбора аргументов.
 */
const fs = require('fs-extra');
const path = require('path');
const os = require('os');
const { program } = require('commander');

// Конфигурация по умолчанию
const defaultConfig = {
    categories: {
        Images: ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.svg', '.webp'],
        Documents: ['.pdf', '.doc', '.docx', '.txt', '.md', '.xls', '.xlsx', '.ppt', '.pptx', '.odt', '.ods'],
        Archives: ['.zip', '.rar', '.7z', '.tar', '.gz', '.bz2', '.xz'],
        Music: ['.mp3', '.wav', '.flac', '.aac', '.ogg', '.m4a'],
        Video: ['.mp4', '.avi', '.mkv', '.mov', '.wmv', '.flv', '.webm'],
        Programs: ['.exe', '.msi', '.dmg', '.appimage', '.deb', '.rpm', '.sh', '.bat'],
        Others: []
    },
    excluded_folders: ['Desktop Organizer', 'System Volume Information'],
    excluded_files: ['desktop.ini', '.DS_Store', 'Thumbs.db'],
    auto_sort_on_start: true
};

class DesktopOrganizer {
    constructor(configPath) {
        this.config = this.loadConfig(configPath);
        this.history = [];
        this.logPath = path.join(os.homedir(), 'organizer.log');
        this.setupLogging();
    }

    loadConfig(configPath) {
        try {
            if (configPath && fs.existsSync(configPath)) {
                return JSON.parse(fs.readFileSync(configPath, 'utf8'));
            }
        } catch (e) {}
        // Создаём конфиг по умолчанию
        const defaultPath = path.join(process.cwd(), 'config.json');
        if (!fs.existsSync(defaultPath)) {
            fs.writeFileSync(defaultPath, JSON.stringify(defaultConfig, null, 2), 'utf8');
        }
        return defaultConfig;
    }

    setupLogging() {
        this.log = (msg) => {
            const line = `[${new Date().toISOString()}] ${msg}`;
            console.log(line);
            fs.appendFileSync(this.logPath, line + '\n');
        };
    }

    getCategory(filename) {
        const ext = path.extname(filename).toLowerCase();
        for (const [cat, exts] of Object.entries(this.config.categories)) {
            if (exts.includes(ext)) return cat;
        }
        return 'Others';
    }

    organize(folderPath, dryRun = false) {
        if (!fs.existsSync(folderPath)) {
            this.log(`Папка ${folderPath} не существует`);
            return 0;
        }
        this.history = [];
        const folder = path.resolve(folderPath);
        // Создаём папки категорий
        const catDirs = {};
        for (const cat of Object.keys(this.config.categories)) {
            const dir = path.join(folder, cat);
            if (!fs.existsSync(dir) && !dryRun) {
                fs.mkdirSync(dir, { recursive: true });
            }
            catDirs[cat] = dir;
        }

        const excludedFolders = new Set(this.config.excluded_folders);
        const excludedFiles = new Set(this.config.excluded_files);
        let moved = 0;

        const items = fs.readdirSync(folder);
        for (const item of items) {
            const itemPath = path.join(folder, item);
            const stat = fs.statSync(itemPath);
            if (stat.isDirectory()) {
                if (excludedFolders.has(item)) continue;
                continue; // не обрабатываем папки
            }
            if (excludedFiles.has(item)) continue;
            const cat = this.getCategory(item);
            const destDir = catDirs[cat] || path.join(folder, 'Others');
            if (cat === 'Others' && !fs.existsSync(destDir)) {
                if (!dryRun) fs.mkdirSync(destDir, { recursive: true });
            }
            let destPath = path.join(destDir, item);
            // Если файл уже существует, добавить счётчик
            if (fs.existsSync(destPath)) {
                const base = path.basename(item, path.extname(item));
                const ext = path.extname(item);
                let counter = 1;
                while (true) {
                    const newName = `${base}_${counter}${ext}`;
                    const newPath = path.join(destDir, newName);
                    if (!fs.existsSync(newPath)) {
                        destPath = newPath;
                        break;
                    }
                    counter++;
                }
            }
            if (!dryRun) {
                fs.moveSync(itemPath, destPath);
                this.history.push({ from: destPath, to: itemPath }); // для undo
                this.log(`Перемещён ${item} -> ${cat}/`);
            }
            moved++;
        }
        this.log(`Обработано ${moved} файлов`);
        return moved;
    }

    undoLast(count = 1) {
        let undone = 0;
        for (let i = 0; i < count && this.history.length > 0; i++) {
            const { from, to } = this.history.pop();
            if (fs.existsSync(from)) {
                fs.moveSync(from, to);
                this.log(`Отмена: ${path.basename(from)} возвращён`);
                undone++;
            } else {
                this.log(`Файл ${from} уже не существует`);
            }
        }
        return undone;
    }
}

// ============ CLI ============
program
    .option('-p, --path <path>', 'Папка для сортировки', path.join(os.homedir(), 'Desktop'))
    .option('-c, --config <path>', 'Путь к конфигурационному файлу')
    .option('-d, --dry-run', 'Только показать, что будет сделано')
    .option('-u, --undo <number>', 'Отменить N последних действий', parseInt, 0)
    .parse(process.argv);

const opts = program.opts();
const organizer = new DesktopOrganizer(opts.config);
if (opts.undo > 0) {
    const undone = organizer.undoLast(opts.undo);
    console.log(`Отменено ${undone} действий`);
} else {
    organizer.organize(opts.path, opts.dryRun);
}
