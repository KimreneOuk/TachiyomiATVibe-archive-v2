import React, { useState, useEffect } from 'react';
import { Download, Upload, ShieldCheck, Database, FileArchive, CheckCircle2, RefreshCw } from 'lucide-react';
import { TRANSLATIONS } from '../i18n';

export default function BackupView({ onToast, lang = 'en' }) {
  const [backupInfo, setBackupInfo] = useState('');
  const [uploadFile, setUploadFile] = useState(null);
  const [loading, setLoading] = useState(false);

  const t = TRANSLATIONS[lang] || TRANSLATIONS.en;

  const fetchBackupList = async () => {
    try {
      const resp = await fetch('/api/backup/list');
      if (resp.ok) {
        const data = await resp.json();
        setBackupInfo(data.info_markdown || '');
      }
    } catch (err) {
      console.error('Error fetching backups:', err);
    }
  };

  useEffect(() => {
    fetchBackupList();
  }, []);

  const handleExportBackup = () => {
    window.open('/api/backup/export', '_blank');
    onToast('Backup download started', 'info');
  };

  const handleImportBackup = async () => {
    if (!uploadFile) {
      onToast('Select a backup file (.zip)', 'warning');
      return;
    }

    setLoading(true);
    try {
      const formData = new FormData();
      formData.append('file', uploadFile);

      const resp = await fetch('/api/backup/import', {
        method: 'POST',
        body: formData,
      });

      if (resp.ok) {
        const data = await resp.json();
        onToast(data.message, 'success');
        fetchBackupList();
      } else {
        onToast('Error restoring backup', 'error');
      }
    } catch (err) {
      onToast('Network error during import', 'error');
    } finally {
      setLoading(false);
    }
  };

  const [isDragging, setIsDragging] = useState(false);

  const handleFileSelect = (file) => {
    if (file && file.name.endsWith('.zip')) {
      setUploadFile(file);
    } else {
      onToast('Please select a valid .zip backup file', 'warning');
    }
  };

  const handleDragOver = (e) => {
    e.preventDefault();
    e.stopPropagation();
    if (!isDragging) setIsDragging(true);
  };

  const handleDragLeave = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      handleFileSelect(e.dataTransfer.files[0]);
      e.dataTransfer.clearData();
    }
  };

  return (
    <div className="w-full h-full flex flex-col space-y-6 animate-fade-in p-2">
      <div className="glass-panel p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="p-3 rounded-2xl bg-gradient-to-tr from-indigo-600 to-violet-600 text-white shadow-md">
            <Database size={28} />
          </div>
          <div>
            <h2 className="text-2xl font-bold text-[var(--text-main)]">{t.backupTitle}</h2>
            <p className="text-sm text-[var(--text-dim)] font-medium">{t.backupSubtitle}</p>
          </div>
        </div>

        <button
          onClick={fetchBackupList}
          className="p-3 rounded-xl border border-[var(--border)] text-[var(--text-dim)] hover:text-[var(--accent)] transition-colors cursor-pointer self-start md:self-auto"
          title={t.refreshLibrary}
        >
          <RefreshCw size={20} />
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 flex-1 min-h-[450px]">
        <div className="glass-panel p-8 flex flex-col justify-between space-y-6">
          <div className="space-y-4">
            <div className="w-16 h-16 rounded-2xl bg-blue-500/10 text-blue-600 flex items-center justify-center">
              <Download size={32} />
            </div>
            <div>
              <h3 className="text-xl font-bold text-[var(--text-main)] mb-1">{t.exportTitle}</h3>
              <p className="text-xs text-[var(--text-soft)] leading-relaxed">
                {t.exportDesc}
              </p>
            </div>
            
            <div className="p-4 rounded-xl bg-[var(--bg-input)] border border-[var(--border)] space-y-2 text-xs text-[var(--text-soft)]">
              <div className="flex items-center justify-between font-medium">
                <span>Base directory:</span>
                <span className="font-mono text-[var(--accent)]">backups/</span>
              </div>
              <div className="flex items-center justify-between font-medium">
                <span>Output format:</span>
                <span className="font-mono font-bold">manga_coloring_tool_backup_YYYYMMDD_HHMMSS.zip</span>
              </div>
            </div>
          </div>

          <button
            onClick={handleExportBackup}
            className="w-full py-4 rounded-xl bg-[var(--accent)] text-white font-bold text-sm hover:bg-[var(--accent-hover)] transition-all flex items-center justify-center gap-2 shadow-lg cursor-pointer"
          >
            <Download size={18} /> {t.exportBtn}
          </button>
        </div>

        <div className="glass-panel p-8 flex flex-col justify-between space-y-6">
          <div className="space-y-4">
            <div className="w-16 h-16 rounded-2xl bg-purple-500/10 text-purple-600 flex items-center justify-center">
              <Upload size={32} />
            </div>
            <div>
              <h3 className="text-xl font-bold text-[var(--text-main)] mb-1">{t.importTitle}</h3>
              <p className="text-xs text-[var(--text-soft)] leading-relaxed">
                {t.importDesc}
              </p>
            </div>

            <label
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
              className={`flex flex-col items-center justify-center border-2 border-dashed rounded-xl p-6 bg-[var(--bg-card)] cursor-pointer transition-all space-y-2 text-center ${
                isDragging ? 'border-[var(--accent)] bg-[var(--accent-glow)] scale-[0.99]' : 'border-[var(--border-lit)] hover:border-[var(--accent)]'
              }`}
            >
              <input
                type="file"
                accept=".zip"
                onChange={(e) => handleFileSelect(e.target.files[0])}
                className="hidden"
              />
              <FileArchive size={28} className="text-[var(--accent)]" />
              <span className="text-xs font-bold text-[var(--text-main)]">
                {uploadFile ? uploadFile.name : t.selectZipPlaceholder}
              </span>
              <span className="text-[11px] text-[var(--text-dim)]">{t.acceptedFormat}</span>
            </label>
          </div>

          <button
            onClick={handleImportBackup}
            disabled={loading || !uploadFile}
            className="w-full py-4 rounded-xl bg-[var(--bg-card)] border-2 border-[var(--border-lit)] text-[var(--text-main)] font-bold text-sm hover:border-[var(--accent)] hover:text-[var(--accent)] disabled:opacity-40 transition-all flex items-center justify-center gap-2 cursor-pointer"
          >
            {loading ? <RefreshCw size={18} className="animate-spin" /> : <Upload size={18} />} {t.importBtn}
          </button>
        </div>
      </div>

      {backupInfo && (
        <div className="glass-panel p-6 space-y-3">
          <h4 className="font-bold text-sm text-[var(--text-main)]">{t.backupsFolderInfo}</h4>
          <div className="p-4 rounded-xl bg-[var(--bg-input)] border border-[var(--border)] font-mono text-xs text-[var(--text-soft)] leading-relaxed whitespace-pre-line">
            {backupInfo}
          </div>
        </div>
      )}
    </div>
  );
}
