<?php
// desktop_organizer.php - Органайзер рабочего стола на PHP (веб + CLI)
// CLI: php desktop_organizer.php --path=/home/user/Desktop --dry-run
// Веб: запустить сервер, открыть в браузере

if (php_sapi_name() === 'cli') {
    // CLI режим
    $options = getopt("", ["path:", "dry-run", "undo::"]);
    $path = $options['path'] ?? getenv('HOME') . '/Desktop';
    $dryRun = isset($options['dry-run']);
    $undo = isset($options['undo']) ? (int)$options['undo'] : 0;
    runCLI($path, $dryRun, $undo);
} else {
    // Веб-режим
    ?>
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <title>🧹 Органайзер рабочего стола (PHP)</title>
        <style>
            body { font-family: 'Segoe UI', sans-serif; background: #f4f7fb; margin: 20px; }
            .container { max-width: 1100px; margin: 0 auto; background: white; padding: 20px; border-radius: 16px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
            h1 { display: flex; align-items: center; gap: 10px; }
            .form-group { margin-bottom: 15px; }
            label { display: inline-block; width: 120px; font-weight: bold; }
            input, button { padding: 6px 12px; border-radius: 6px; border: 1px solid #ccc; }
            button { background: #3498db; color: white; border: none; cursor: pointer; }
            table { width: 100%; border-collapse: collapse; margin-top: 20px; }
            th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
            th { background: #2c3e50; color: white; }
            .log { background: #f9f9f9; padding: 10px; max-height: 200px; overflow-y: auto; border: 1px solid #ddd; margin-top: 20px; }
        </style>
    </head>
    <body>
    <div class="container">
        <h1>🧹 Органайзер рабочего стола</h1>
        <form method="POST">
            <div class="form-group"><label>Папка:</label><input type="text" name="path" value="<?= htmlspecialchars($_POST['path'] ?? getenv('HOME').'/Desktop') ?>" size="60"></div>
            <div class="form-group"><label>Только показать:</label><input type="checkbox" name="dry_run" <?= isset($_POST['dry_run']) ? 'checked' : '' ?>></div>
            <button type="submit">▶️ Запустить сортировку</button>
            <button type="submit" name="undo" value="1">↩️ Отменить последнее</button>
        </form>
        <?php
        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            $path = $_POST['path'] ?? '';
            $dryRun = isset($_POST['dry_run']);
            $undo = isset($_POST['undo']);
            if ($undo) {
                undoLast();
            } else {
                sortFiles($path, $dryRun);
            }
        }
        ?>
        <h3>Журнал действий</h3>
        <div class="log" id="log"><?php echo nl2br(file_exists('organizer.log') ? file_get_contents('organizer.log') : 'Лог пуст'); ?></div>
    </div>
    </body>
    </html>
    <?php
}

// ============ Общие функции ============
function loadConfig() {
    $default = [
        'categories' => [
            'Images' => ['.jpg','.jpeg','.png','.gif','.bmp','.svg','.webp'],
            'Documents' => ['.pdf','.doc','.docx','.txt','.md','.xls','.xlsx','.ppt','.pptx','.odt','.ods'],
            'Archives' => ['.zip','.rar','.7z','.tar','.gz','.bz2','.xz'],
            'Music' => ['.mp3','.wav','.flac','.aac','.ogg','.m4a'],
            'Video' => ['.mp4','.avi','.mkv','.mov','.wmv','.flv','.webm'],
            'Programs' => ['.exe','.msi','.dmg','.appimage','.deb','.rpm','.sh','.bat'],
            'Others' => []
        ],
        'excluded_folders' => ['Desktop Organizer', 'System Volume Information'],
        'excluded_files' => ['desktop.ini', '.DS_Store', 'Thumbs.db']
    ];
    if (file_exists('config.json')) {
        $cfg = json_decode(file_get_contents('config.json'), true);
        if ($cfg) $default = $cfg;
    } else {
        file_put_contents('config.json', json_encode($default, JSON_PRETTY_PRINT));
    }
    return $default;
}

function logMessage($msg) {
    $line = date('Y-m-d H:i:s') . ' - ' . $msg . "\n";
    file_put_contents('organizer.log', $line, FILE_APPEND);
    echo $line;
}

function getCategory($filename, $config) {
    $ext = strtolower(pathinfo($filename, PATHINFO_EXTENSION));
    if ($ext) $ext = '.' . $ext;
    foreach ($config['categories'] as $cat => $exts) {
        if (in_array($ext, $exts)) return $cat;
    }
    return 'Others';
}

function sortFiles($path, $dryRun) {
    $config = loadConfig();
    if (!is_dir($path)) {
        logMessage("Папка $path не существует");
        return;
    }
    $history = [];
    $excludedFolders = array_flip($config['excluded_folders']);
    $excludedFiles = array_flip($config['excluded_files']);

    // создаём папки категорий
    $catDirs = [];
    foreach (array_keys($config['categories']) as $cat) {
        $dir = $path . DIRECTORY_SEPARATOR . $cat;
        if (!is_dir($dir) && !$dryRun) {
            mkdir($dir, 0755, true);
        }
        $catDirs[$cat] = $dir;
    }

    $files = scandir($path);
    $moved = 0;
    foreach ($files as $file) {
        if ($file === '.' || $file === '..') continue;
        $fullPath = $path . DIRECTORY_SEPARATOR . $file;
        if (is_dir($fullPath)) {
            if (isset($excludedFolders[$file])) continue;
            continue;
        }
        if (isset($excludedFiles[$file])) continue;
        $cat = getCategory($file, $config);
        $destDir = $catDirs[$cat] ?? $path . DIRECTORY_SEPARATOR . 'Others';
        if ($cat === 'Others' && !is_dir($destDir) && !$dryRun) {
            mkdir($destDir, 0755, true);
        }
        $destFile = $destDir . DIRECTORY_SEPARATOR . $file;
        // дубликаты
        if (file_exists($destFile)) {
            $base = pathinfo($file, PATHINFO_FILENAME);
            $ext = pathinfo($file, PATHINFO_EXTENSION);
            $ext = $ext ? '.' . $ext : '';
            $counter = 1;
            while (true) {
                $newName = $base . '_' . $counter . $ext;
                $candidate = $destDir . DIRECTORY_SEPARATOR . $newName;
                if (!file_exists($candidate)) {
                    $destFile = $candidate;
                    break;
                }
                $counter++;
            }
        }
        if (!$dryRun) {
            if (rename($fullPath, $destFile)) {
                $history[] = ['from' => $destFile, 'to' => $fullPath];
                logMessage("Перемещён $file -> $cat/");
            } else {
                logMessage("Ошибка перемещения $file");
            }
        } else {
            logMessage("(dry-run) $file -> $cat/");
        }
        $moved++;
    }
    if (!$dryRun && !empty($history)) {
        $old = file_exists('history.json') ? json_decode(file_get_contents('history.json'), true) : [];
        file_put_contents('history.json', json_encode(array_merge($old, $history), JSON_PRETTY_PRINT));
    }
    logMessage("Обработано $moved файлов");
}

function undoLast() {
    if (!file_exists('history.json')) {
        logMessage("Нет истории для отмены");
        return;
    }
    $history = json_decode(file_get_contents('history.json'), true);
    if (empty($history)) {
        logMessage("История пуста");
        return;
    }
    $last = array_pop($history);
    file_put_contents('history.json', json_encode($history, JSON_PRETTY_PRINT));
    if (file_exists($last['from'])) {
        if (rename($last['from'], $last['to'])) {
            logMessage("Отмена: " . basename($last['from']) . " возвращён");
        } else {
            logMessage("Ошибка отмены");
        }
    } else {
        logMessage("Файл " . basename($last['from']) . " уже не существует");
    }
}

function runCLI($path, $dryRun, $undo) {
    if ($undo > 0) {
        // отменить N действий (упрощённо — последнее)
        undoLast();
    } else {
        sortFiles($path, $dryRun);
    }
}
?>
