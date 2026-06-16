// DesktopOrganizer.java - Органайзер рабочего стола на Java (Swing GUI)
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DesktopOrganizer extends JFrame {
    private JTextField folderField;
    private JCheckBox dryRunCheck;
    private JTable fileTable;
    private DefaultTableModel tableModel;
    private JTextArea logArea;
    private JLabel statusLabel;
    private File currentFolder;
    private List<Map<String, String>> history = new ArrayList<>();
    private Map<String, List<String>> categories = new HashMap<>();
    private List<String> excludedFolders = Arrays.asList("Desktop Organizer", "System Volume Information");
    private List<String> excludedFiles = Arrays.asList("desktop.ini", ".DS_Store", "Thumbs.db");
    private String configPath = "config.json";

    public DesktopOrganizer() {
        setTitle("🧹 Органайзер рабочего стола");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 650);
        setLocationRelativeTo(null);
        loadConfig();
        initUI();
    }

    private void loadConfig() {
        // Загрузка из JSON (упрощённо — можно реализовать через Jackson)
        // Здесь используем встроенные настройки
        categories.put("Images", Arrays.asList(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".webp"));
        categories.put("Documents", Arrays.asList(".pdf", ".doc", ".docx", ".txt", ".md", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods"));
        categories.put("Archives", Arrays.asList(".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz"));
        categories.put("Music", Arrays.asList(".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a"));
        categories.put("Video", Arrays.asList(".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm"));
        categories.put("Programs", Arrays.asList(".exe", ".msi", ".dmg", ".appimage", ".deb", ".rpm", ".sh", ".bat"));
        categories.put("Others", new ArrayList<>());
    }

    private void initUI() {
        // Верхняя панель
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel folderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        folderPanel.add(new JLabel("Папка:"));
        folderField = new JTextField(System.getProperty("user.home") + File.separator + "Desktop", 40);
        folderPanel.add(folderField);
        JButton browseBtn = new JButton("Обзор...");
        browseBtn.addActionListener(e -> browseFolder());
        folderPanel.add(browseBtn);
        topPanel.add(folderPanel, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        dryRunCheck = new JCheckBox("Dry-run (только показать)");
        controlPanel.add(dryRunCheck);
        JButton sortBtn = new JButton("▶️ Сортировать");
        sortBtn.addActionListener(e -> sortFiles());
        controlPanel.add(sortBtn);
        JButton undoBtn = new JButton("↩️ Отменить последнее");
        undoBtn.addActionListener(e -> undoLast());
        controlPanel.add(undoBtn);
        topPanel.add(controlPanel, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        // Таблица файлов (предпросмотр)
        tableModel = new DefaultTableModel(new String[]{"Имя", "Категория", "Действие"}, 0);
        fileTable = new JTable(tableModel);
        fileTable.setAutoCreateRowSorter(true);
        JScrollPane scrollTable = new JScrollPane(fileTable);
        scrollTable.setBorder(BorderFactory.createTitledBorder("Файлы в папке"));
        add(scrollTable, BorderLayout.CENTER);

        // Лог
        logArea = new JTextArea(8, 80);
        logArea.setEditable(false);
        JScrollPane scrollLog = new JScrollPane(logArea);
        scrollLog.setBorder(BorderFactory.createTitledBorder("Журнал"));
        add(scrollLog, BorderLayout.SOUTH);

        // Статус
        statusLabel = new JLabel("Готов", SwingConstants.LEFT);
        add(statusLabel, BorderLayout.SOUTH);

        // Загружаем список файлов в таблицу при старте
        refreshFileList();
    }

    private void browseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            folderField.setText(fc.getSelectedFile().getAbsolutePath());
            refreshFileList();
        }
    }

    private void refreshFileList() {
        tableModel.setRowCount(0);
        File folder = new File(folderField.getText());
        if (!folder.exists() || !folder.isDirectory()) {
            log("Папка не существует или не является директорией");
            return;
        }
        currentFolder = folder;
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) continue;
            String cat = getCategory(f.getName());
            tableModel.addRow(new Object[]{f.getName(), cat, "Ожидает"});
        }
        statusLabel.setText("Показано файлов: " + (files.length - countDirectories(files)));
    }

    private int countDirectories(File[] files) {
        int count = 0;
        for (File f : files) if (f.isDirectory()) count++;
        return count;
    }

    private String getCategory(String filename) {
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) ext = filename.substring(dot).toLowerCase();
        for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
            if (entry.getValue().contains(ext)) {
                return entry.getKey();
            }
        }
        return "Others";
    }

    private void sortFiles() {
        File folder = new File(folderField.getText());
        if (!folder.exists() || !folder.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Укажите корректную папку");
            return;
        }
        boolean dryRun = dryRunCheck.isSelected();
        log("Начинаем сортировку в " + folder.getAbsolutePath() + (dryRun ? " (dry-run)" : ""));
        history.clear();

        // Создаём папки категорий
        Map<String, File> catDirs = new HashMap<>();
        for (String cat : categories.keySet()) {
            File dir = new File(folder, cat);
            if (!dir.exists() && !dryRun) {
                dir.mkdir();
            }
            catDirs.put(cat, dir);
        }

        File[] files = folder.listFiles();
        if (files == null) return;
        int moved = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                if (excludedFolders.contains(f.getName())) continue;
                continue;
            }
            if (excludedFiles.contains(f.getName())) continue;
            String cat = getCategory(f.getName());
            File destDir = catDirs.getOrDefault(cat, new File(folder, "Others"));
            if (cat.equals("Others") && !destDir.exists() && !dryRun) {
                destDir.mkdir();
            }
            File destFile = new File(destDir, f.getName());
            // если существует, добавить счётчик
            if (destFile.exists()) {
                String name = f.getName();
                String base = name;
                String ext = "";
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    base = name.substring(0, dot);
                    ext = name.substring(dot);
                }
                int counter = 1;
                while (true) {
                    String newName = base + "_" + counter + ext;
                    File candidate = new File(destDir, newName);
                    if (!candidate.exists()) {
                        destFile = candidate;
                        break;
                    }
                    counter++;
                }
            }
            if (!dryRun) {
                try {
                    Files.move(f.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    history.add(Map.of("from", destFile.getAbsolutePath(), "to", f.getAbsolutePath()));
                    log("Перемещён " + f.getName() + " -> " + cat + "/");
                } catch (IOException e) {
                    log("Ошибка при перемещении " + f.getName() + ": " + e.getMessage());
                }
            } else {
                log("(dry-run) " + f.getName() + " -> " + cat + "/");
            }
            moved++;
        }
        statusLabel.setText("Обработано " + moved + " файлов");
        log("Сортировка завершена");
        refreshFileList();
    }

    private void undoLast() {
        if (history.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Нет действий для отмены");
            return;
        }
        Map<String, String> last = history.remove(history.size() - 1);
        File from = new File(last.get("from"));
        File to = new File(last.get("to"));
        if (!from.exists()) {
            log("Файл " + from.getName() + " уже не существует");
            return;
        }
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log("Отмена: " + from.getName() + " возвращён");
            statusLabel.setText("Отменено перемещение");
        } catch (IOException e) {
            log("Ошибка при отмене: " + e.getMessage());
        }
        refreshFileList();
    }

    private void log(String msg) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logArea.append("[" + time + "] " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new DesktopOrganizer().setVisible(true));
    }
}
