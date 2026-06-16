// DesktopOrganizer.cs - Органайзер рабочего стола на C# (Windows Forms)
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows.Forms;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Diagnostics;

namespace DesktopOrganizer
{
    public class Config
    {
        public Dictionary<string, List<string>> Categories { get; set; } = new();
        public List<string> ExcludedFolders { get; set; } = new();
        public List<string> ExcludedFiles { get; set; } = new();
        public bool AutoSortOnStart { get; set; } = true;
    }

    public partial class MainForm : Form
    {
        private TextBox folderBox;
        private CheckBox dryRunCheck;
        private DataGridView fileGrid;
        private RichTextBox logBox;
        private Label statusLabel;
        private Config config;
        private List<HistoryEntry> history = new();

        public MainForm()
        {
            InitializeComponent();
            LoadConfig();
            RefreshFileList();
        }

        private void InitializeComponent()
        {
            this.Text = "🧹 Органайзер рабочего стола";
            this.Size = new System.Drawing.Size(1000, 700);
            this.StartPosition = FormStartPosition.CenterScreen;

            // Верхняя панель
            var topPanel = new TableLayoutPanel { Dock = DockStyle.Top, RowCount = 2, Padding = new Padding(10) };
            topPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            topPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));

            var folderPanel = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight };
            folderPanel.Controls.Add(new Label { Text = "Папка:", AutoSize = true });
            folderBox = new TextBox { Width = 400, Text = Environment.GetFolderPath(Environment.SpecialFolder.Desktop) };
            folderPanel.Controls.Add(folderBox);
            var browseBtn = new Button { Text = "Обзор..." };
            browseBtn.Click += (s, e) => {
                using var fbd = new FolderBrowserDialog();
                if (fbd.ShowDialog() == DialogResult.OK) {
                    folderBox.Text = fbd.SelectedPath;
                    RefreshFileList();
                }
            };
            folderPanel.Controls.Add(browseBtn);
            topPanel.Controls.Add(folderPanel, 0, 0);

            var controlPanel = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight };
            dryRunCheck = new CheckBox { Text = "Только показать (dry-run)", AutoSize = true };
            controlPanel.Controls.Add(dryRunCheck);
            var sortBtn = new Button { Text = "▶️ Сортировать" };
            sortBtn.Click += (s, e) => SortFiles();
            controlPanel.Controls.Add(sortBtn);
            var undoBtn = new Button { Text = "↩️ Отменить последнее" };
            undoBtn.Click += (s, e) => UndoLast();
            controlPanel.Controls.Add(undoBtn);
            topPanel.Controls.Add(controlPanel, 0, 1);
            this.Controls.Add(topPanel);

            // Таблица файлов
            fileGrid = new DataGridView { Dock = DockStyle.Fill, AllowUserToAddRows = false, ReadOnly = true, AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill };
            fileGrid.Columns.Add("Name", "Имя");
            fileGrid.Columns.Add("Category", "Категория");
            fileGrid.Columns.Add("Action", "Действие");
            this.Controls.Add(fileGrid);

            // Лог
            logBox = new RichTextBox { Dock = DockStyle.Bottom, Height = 150, ReadOnly = true, BackColor = Color.WhiteSmoke };
            this.Controls.Add(logBox);

            statusLabel = new Label { Text = "Готов", Dock = DockStyle.Bottom, Height = 25 };
            this.Controls.Add(statusLabel);
        }

        private void LoadConfig()
        {
            string configPath = "config.json";
            if (File.Exists(configPath))
            {
                string json = File.ReadAllText(configPath);
                config = JsonSerializer.Deserialize<Config>(json) ?? GetDefaultConfig();
            }
            else
            {
                config = GetDefaultConfig();
                File.WriteAllText(configPath, JsonSerializer.Serialize(config, new JsonSerializerOptions { WriteIndented = true }));
            }
        }

        private Config GetDefaultConfig()
        {
            return new Config
            {
                Categories = new Dictionary<string, List<string>>
                {
                    ["Images"] = new() { ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".webp" },
                    ["Documents"] = new() { ".pdf", ".doc", ".docx", ".txt", ".md", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods" },
                    ["Archives"] = new() { ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz" },
                    ["Music"] = new() { ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a" },
                    ["Video"] = new() { ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm" },
                    ["Programs"] = new() { ".exe", ".msi", ".dmg", ".appimage", ".deb", ".rpm", ".sh", ".bat" },
                    ["Others"] = new()
                },
                ExcludedFolders = new() { "Desktop Organizer", "System Volume Information" },
                ExcludedFiles = new() { "desktop.ini", ".DS_Store", "Thumbs.db" },
                AutoSortOnStart = true
            };
        }

        private void RefreshFileList()
        {
            fileGrid.Rows.Clear();
            string folder = folderBox.Text;
            if (!Directory.Exists(folder)) return;
            var files = Directory.GetFiles(folder);
            foreach (var f in files)
            {
                string name = Path.GetFileName(f);
                if (config.ExcludedFiles.Contains(name)) continue;
                string cat = GetCategory(name);
                fileGrid.Rows.Add(name, cat, "Ожидает");
            }
            statusLabel.Text = $"Показано файлов: {fileGrid.Rows.Count}";
        }

        private string GetCategory(string filename)
        {
            string ext = Path.GetExtension(filename).ToLower();
            foreach (var kv in config.Categories)
            {
                if (kv.Value.Contains(ext))
                    return kv.Key;
            }
            return "Others";
        }

        private void SortFiles()
        {
            string folder = folderBox.Text;
            if (!Directory.Exists(folder))
            {
                MessageBox.Show("Папка не существует");
                return;
            }
            bool dryRun = dryRunCheck.Checked;
            Log($"Начинаем сортировку в {folder}" + (dryRun ? " (dry-run)" : ""));
            history.Clear();

            // Создаём папки категорий
            var catDirs = new Dictionary<string, string>();
            foreach (var cat in config.Categories.Keys)
            {
                string dir = Path.Combine(folder, cat);
                if (!Directory.Exists(dir) && !dryRun)
                    Directory.CreateDirectory(dir);
                catDirs[cat] = dir;
            }

            var files = Directory.GetFiles(folder);
            int moved = 0;
            foreach (var f in files)
            {
                string name = Path.GetFileName(f);
                if (config.ExcludedFiles.Contains(name)) continue;
                if (Directory.Exists(f)) continue; // не папки
                string cat = GetCategory(name);
                string destDir = catDirs.TryGetValue(cat, out var d) ? d : Path.Combine(folder, "Others");
                if (cat == "Others" && !Directory.Exists(destDir) && !dryRun)
                    Directory.CreateDirectory(destDir);
                string destFile = Path.Combine(destDir, name);
                // обработка дубликатов
                if (File.Exists(destFile))
                {
                    string baseName = Path.GetFileNameWithoutExtension(name);
                    string ext = Path.GetExtension(name);
                    int counter = 1;
                    while (true)
                    {
                        string newName = $"{baseName}_{counter}{ext}";
                        string candidate = Path.Combine(destDir, newName);
                        if (!File.Exists(candidate))
                        {
                            destFile = candidate;
                            break;
                        }
                        counter++;
                    }
                }
                if (!dryRun)
                {
                    File.Move(f, destFile);
                    history.Add(new HistoryEntry { From = destFile, To = f });
                    Log($"Перемещён {name} -> {cat}/");
                }
                else
                {
                    Log($"(dry-run) {name} -> {cat}/");
                }
                moved++;
            }
            statusLabel.Text = $"Обработано {moved} файлов";
            Log("Сортировка завершена");
            RefreshFileList();
        }

        private void UndoLast()
        {
            if (history.Count == 0)
            {
                MessageBox.Show("Нет действий для отмены");
                return;
            }
            var last = history[history.Count - 1];
            history.RemoveAt(history.Count - 1);
            if (File.Exists(last.From))
            {
                File.Move(last.From, last.To);
                Log($"Отмена: {Path.GetFileName(last.From)} возвращён");
            }
            else
            {
                Log($"Файл {last.From} уже не существует");
            }
            RefreshFileList();
        }

        private void Log(string msg)
        {
            string time = DateTime.Now.ToString("HH:mm:ss");
            logBox.AppendText($"[{time}] {msg}\n");
            logBox.ScrollToCaret();
        }
    }

    public class HistoryEntry
    {
        public string From { get; set; }
        public string To { get; set; }
    }

    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.Run(new MainForm());
        }
    }
}
