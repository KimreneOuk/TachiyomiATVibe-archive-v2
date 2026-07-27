import React, { useState, useEffect } from 'react';
import {
  Wand2, BookOpen, Library, Database, ShieldCheck,
  Moon, Sun, Play, Square, Image as ImageIcon, Sparkles, FolderUp,
  Layers, ChevronRight, ChevronLeft, X,
  Clock, Hourglass, Check, CheckCircle2, Zap, Scale, Palette,
  PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen, List, LayoutGrid, Award, Globe
} from 'lucide-react';

import BeforeAfterSlider from './components/BeforeAfterSlider';
import PaletteExtractor from './components/PaletteExtractor';
import MangaReaderView from './components/MangaReaderView';
import LibraryView from './components/LibraryView';
import BackupView from './components/BackupView';
import WatermarkView from './components/WatermarkView';
import { TRANSLATIONS } from './i18n';

const PUBLIC_MODES = [
  {
    id: 'ultrafast',
    iconComponent: Zap,
    prompt: 'colour, high-quality, high-definition, high-resolution, accuracy, consistency',
    steps: 1,
    megapixels: 1.0,
    useRef: false,
    color: 'from-amber-500 to-orange-600',
  },
  {
    id: 'balanced',
    iconComponent: Scale,
    prompt: 'colour, high-quality, high-definition, high-resolution, accuracy, consistency',
    steps: 2,
    megapixels: 1.0,
    useRef: false,
    color: 'from-blue-500 to-indigo-600',
  },
  {
    id: 'quality',
    iconComponent: Sparkles,
    prompt: 'colour, high-quality, high-definition, high-resolution, accuracy, consistency',
    steps: 3,
    megapixels: 2.0,
    useRef: false,
    color: 'from-violet-500 to-purple-600',
  },
  {
    id: 'ref_fast',
    iconComponent: Palette,
    prompt: 'Style Transfer',
    steps: 1,
    megapixels: 1.0,
    useRef: true,
    color: 'from-emerald-500 to-teal-600',
  },
  {
    id: 'ref_balanced',
    iconComponent: Layers,
    prompt: 'style transfer',
    steps: 2,
    megapixels: 1.0,
    useRef: true,
    color: 'from-cyan-500 to-blue-600',
  },
  {
    id: 'ref_quality',
    iconComponent: Award,
    prompt: 'style transfer',
    steps: 3,
    megapixels: 2.0,
    useRef: true,
    color: 'from-fuchsia-500 to-pink-600',
  },
];

export default function App() {
  const [activeTab, setActiveTab] = useState('studio');
  const [selectedModeId, setSelectedModeId] = useState('balanced');
  const [outputFormat, setOutputFormat] = useState('png');

  // Idioma (English por defecto)
  const [lang, setLang] = useState(() => {
    return localStorage.getItem('manga-lang') || 'en';
  });

  const [darkMode, setDarkMode] = useState(() => {
    return localStorage.getItem('cozy-dm-pub-global') === '1';
  });

  const [isLeftOpen, setIsLeftOpen] = useState(true);
  const [isRightOpen, setIsRightOpen] = useState(true);

  const [comfyConnected, setComfyConnected] = useState(false);
  const [comfyAddr, setComfyAddr] = useState('127.0.0.1:8188');

  // Parámetros de Generación
  const [inputMode, setInputMode] = useState('files');
  const [inputFiles, setInputFiles] = useState([]);
  const [folderPath, setFolderPath] = useState('');
  const [sessionName, setSessionName] = useState('manga_chapter_01');

  // Inspección de Imágenes de Entrada
  const [inputViewMode, setInputViewMode] = useState('list');
  const [folderScannedImages, setFolderScannedImages] = useState([]);
  const [isScanningFolder, setIsScanningFolder] = useState(false);

  // Referencia de Color
  const [refFile, setRefFile] = useState(null);
  const [refPath, setRefPath] = useState('');
  const [refPreviewUrl, setRefPreviewUrl] = useState(null);
  const [autoRef, setAutoRef] = useState(false);

  // Generación SSE & Galería
  const [isGenerating, setIsGenerating] = useState(false);
  const [batchProgress, setBatchProgress] = useState({
    status: '',
    stats: { done: 0, total: 0, percent: 0, elapsed: '00:00', eta: '--:--', stage: '', current_file: '' }
  });
  const [gallery, setGallery] = useState([]);
  const [selectedGalleryItem, setSelectedGalleryItem] = useState(null);
  const [currentOriginalUrl, setCurrentOriginalUrl] = useState(null);

  // Toasts
  const [toasts, setToasts] = useState([]);

  const t = TRANSLATIONS[lang] || TRANSLATIONS.en;
  const currentMode = PUBLIC_MODES.find((m) => m.id === selectedModeId) || PUBLIC_MODES[1];
  const currentModeInfo = t.modes[currentMode.id] || t.modes.balanced;

  const toggleLanguage = () => {
    const nextLang = lang === 'en' ? 'es' : 'en';
    setLang(nextLang);
    localStorage.setItem('manga-lang', nextLang);
  };

  const addToast = (message, type = 'info') => {
    const id = Date.now();
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 4000);
  };

  const checkStatus = async () => {
    try {
      const resp = await fetch('/api/status');
      if (resp.ok) {
        const data = await resp.json();
        setComfyConnected(data.comfy_connected);
        setComfyAddr(data.comfy_address);
      }
    } catch (err) {
      setComfyConnected(false);
    }
  };

  useEffect(() => {
    checkStatus();
    const interval = setInterval(checkStatus, 15000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (darkMode) {
      document.body.classList.add('dark');
      document.body.classList.remove('light');
      localStorage.setItem('cozy-dm-pub-global', '1');
    } else {
      document.body.classList.remove('dark');
      document.body.classList.add('light');
      localStorage.setItem('cozy-dm-pub-global', '0');
    }
  }, [darkMode]);

  useEffect(() => {
    if (inputMode === 'folder' && folderPath.trim()) {
      const timer = setTimeout(async () => {
        setIsScanningFolder(true);
        try {
          const resp = await fetch(`/api/scan_folder?folder_path=${encodeURIComponent(folderPath.trim())}`);
          if (resp.ok) {
            const data = await resp.json();
            setFolderScannedImages(data.images || []);
            if (data.images && data.images.length > 0) {
              setCurrentOriginalUrl(data.images[0].url);
            }
          }
        } catch (e) {
          console.error("Error scanning folder:", e);
        } finally {
          setIsScanningFolder(false);
        }
      }, 400);
      return () => clearTimeout(timer);
    } else {
      setFolderScannedImages([]);
    }
  }, [inputMode, folderPath]);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        handleStartGenerate();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [inputMode, inputFiles, folderPath, sessionName, selectedModeId, refFile, refPath, autoRef, outputFormat, isGenerating]);

  const handleInputFilesChange = (files) => {
    setInputFiles(files);
    if (files && files.length > 0) {
      setCurrentOriginalUrl(URL.createObjectURL(files[0]));
    } else {
      setCurrentOriginalUrl(null);
    }
  };

  const handleRemoveInputFile = (indexToRemove) => {
    const fileArray = Array.from(inputFiles).filter((_, idx) => idx !== indexToRemove);
    const dt = new DataTransfer();
    fileArray.forEach((f) => dt.items.add(f));
    setInputFiles(dt.files);
    if (fileArray.length > 0) {
      setCurrentOriginalUrl(URL.createObjectURL(fileArray[0]));
    } else {
      setCurrentOriginalUrl(null);
    }
  };

  const handleRefFileChange = (file) => {
    setRefFile(file);
    if (file) {
      setRefPreviewUrl(URL.createObjectURL(file));
      setRefPath('');
    } else {
      setRefPreviewUrl(null);
    }
  };

  const handlePickFolder = async () => {
    try {
      const resp = await fetch('/api/pick_folder');
      if (resp.ok) {
        const data = await resp.json();
        if (data.folder_path) {
          setInputMode('folder');
          setFolderPath(data.folder_path);
          addToast(t.toastFolderOk, 'success');
        }
      }
    } catch (err) {
      addToast(t.toastFolderErr, 'error');
    }
  };

  const handleStartGenerate = async () => {
    if (inputMode === 'files' && !inputFiles.length) {
      addToast(t.toastReqPages, 'warning');
      return;
    }
    if (inputMode === 'folder' && !folderPath.trim()) {
      addToast(t.toastReqFolder, 'warning');
      return;
    }

    if (currentMode.useRef && !refFile && !refPath && !autoRef) {
      addToast(t.toastReqRef, 'warning');
    }

    setIsGenerating(true);
    setGallery([]);
    setSelectedGalleryItem(null);

    const formData = new FormData();
    formData.append('prompt', currentMode.prompt);
    formData.append('use_ref', currentMode.useRef);
    formData.append('folder_name', sessionName);
    formData.append('steps', currentMode.steps);
    formData.append('auto_ref', autoRef);
    formData.append('cfg', 1.0);
    formData.append('megapixels', currentMode.megapixels);
    formData.append('watermark', true);
    formData.append('use_folder_mode', inputMode === 'folder');
    formData.append('folder_path_input', folderPath);
    formData.append('preset_name', currentModeInfo.name);
    formData.append('output_format', outputFormat);

    if (refFile) {
      formData.append('ref_file', refFile);
    } else if (refPath) {
      formData.append('ref_path', refPath);
    }

    if (inputMode === 'files') {
      Array.from(inputFiles).forEach((f) => {
        formData.append('files', f);
      });
      if (inputFiles[0]) {
        setCurrentOriginalUrl(URL.createObjectURL(inputFiles[0]));
      }
    }

    try {
      const response = await fetch('/api/generate', {
        method: 'POST',
        body: formData,
      });

      const reader = response.body.getReader();
      const decoder = new TextDecoder();

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        const chunk = decoder.decode(value);
        const lines = chunk.split('\n\n');

        lines.forEach((line) => {
          if (line.startsWith('data: ')) {
            try {
              const data = JSON.parse(line.replace('data: ', ''));
              setBatchProgress({
                status: data.status || '',
                stats: data.stats || {},
              });
              if (data.gallery && data.gallery.length) {
                setGallery(data.gallery);
                const latest = data.gallery[data.gallery.length - 1];
                setSelectedGalleryItem(latest);
              }
            } catch (e) {}
          }
        });
      }
    } catch (err) {
      addToast(t.toastGenError, 'error');
    } finally {
      setIsGenerating(false);
    }
  };

  const handleCancelGenerate = async () => {
    try {
      const resp = await fetch('/api/cancel', { method: 'POST' });
      if (resp.ok) {
        addToast(t.toastCancelReq, 'warning');
      }
    } catch (err) {}
  };

  return (
    <div className="h-screen w-screen flex flex-col bg-[var(--bg-root)] text-[var(--text-main)] overflow-hidden font-sans">

      {/* TOP HEADER GLOBAL SUITE */}
      <header className="h-16 border-b border-[var(--border)] bg-[var(--bg-panel)] flex items-center justify-between px-6 shrink-0 z-40 shadow-sm">
        <div className="flex items-center gap-4">
          <div className="w-10 h-10 rounded-2xl bg-gradient-to-tr from-emerald-500 via-teal-600 to-indigo-600 flex items-center justify-center text-white font-black text-xl shadow-md">
            🎨
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="font-bold text-xl text-[var(--text-main)] leading-tight tracking-tight">{t.title}</h1>
              <span className="px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-600 text-xs font-bold font-mono border border-emerald-500/20">{t.publicEdition}</span>
            </div>
            <p className="text-xs text-[var(--text-dim)] font-medium">{t.subtitle}</p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className={`flex items-center gap-2 px-4 py-2 rounded-full text-xs font-bold border transition-all ${
            comfyConnected ? 'bg-emerald-500/10 text-emerald-600 border-emerald-500/20' : 'bg-rose-500/10 text-rose-600 border-rose-500/20'
          }`}>
            <span className={`w-2.5 h-2.5 rounded-full ${comfyConnected ? 'bg-emerald-500 animate-pulse' : 'bg-rose-500'}`} />
            {comfyConnected ? `${t.comfyConnected} (${comfyAddr})` : t.comfyDisconnected}
          </div>

          <button
            onClick={toggleLanguage}
            className="flex items-center gap-2 px-3 py-2 rounded-xl bg-[var(--accent)]/10 text-[var(--accent)] border border-[var(--accent)]/30 hover:bg-[var(--accent)] hover:text-white transition-all cursor-pointer text-xs font-bold shadow-xs"
            title="Toggle Language / Cambiar Idioma"
          >
            <Globe size={16} />
            <span>{lang === 'en' ? 'English (EN)' : 'Español (ES)'}</span>
          </button>

          <button
            onClick={() => setDarkMode(!darkMode)}
            className="flex items-center gap-2 px-3 py-2 rounded-xl glass-card text-[var(--text-soft)] hover:text-[var(--accent)] hover:border-[var(--accent)] transition-all cursor-pointer text-xs font-semibold"
            title="Toggle Theme"
          >
            {darkMode ? <Sun size={18} /> : <Moon size={18} />}
            <span>{darkMode ? t.lightMode : t.darkMode}</span>
          </button>
        </div>
      </header>

      {/* WORKSPACE CONTAINER */}
      <div className="flex-1 flex overflow-hidden">

        {/* SIDEBAR NAVIGATION */}
        <aside className="w-64 border-r border-[var(--border)] bg-[var(--bg-panel)] flex flex-col justify-between py-4 px-3 shrink-0 z-30 space-y-4">
          <div className="space-y-1">
            <span className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-dim)] px-3 mb-2 block">{t.nav}</span>
            
            <button
              onClick={() => setActiveTab('studio')}
              className={`w-full flex items-center justify-between p-3 rounded-xl transition-all duration-200 cursor-pointer ${
                activeTab === 'studio'
                  ? 'bg-[var(--accent)] text-white shadow-md font-bold'
                  : 'text-[var(--text-soft)] hover:bg-black/5 dark:hover:bg-white/5 hover:text-[var(--text-main)] font-semibold'
              }`}
            >
              <div className="flex items-center gap-3">
                <Wand2 size={20} />
                <div className="text-left">
                  <span className="block text-sm leading-tight">{t.studioAI}</span>
                  <span className={`text-[11px] font-normal block ${activeTab === 'studio' ? 'text-white/80' : 'text-[var(--text-dim)]'}`}>{t.studioSub}</span>
                </div>
              </div>
              {activeTab === 'studio' && <ChevronRight size={16} />}
            </button>

            <button
              onClick={() => setActiveTab('reader')}
              className={`w-full flex items-center justify-between p-3 rounded-xl transition-all duration-200 cursor-pointer ${
                activeTab === 'reader'
                  ? 'bg-[var(--accent)] text-white shadow-md font-bold'
                  : 'text-[var(--text-soft)] hover:bg-black/5 dark:hover:bg-white/5 hover:text-[var(--text-main)] font-semibold'
              }`}
            >
              <div className="flex items-center gap-3">
                <BookOpen size={20} />
                <div className="text-left">
                  <span className="block text-sm leading-tight">{t.reader}</span>
                  <span className={`text-[11px] font-normal block ${activeTab === 'reader' ? 'text-white/80' : 'text-[var(--text-dim)]'}`}>{t.readerSub}</span>
                </div>
              </div>
              {activeTab === 'reader' && <ChevronRight size={16} />}
            </button>

            <button
              onClick={() => setActiveTab('library')}
              className={`w-full flex items-center justify-between p-3 rounded-xl transition-all duration-200 cursor-pointer ${
                activeTab === 'library'
                  ? 'bg-[var(--accent)] text-white shadow-md font-bold'
                  : 'text-[var(--text-soft)] hover:bg-black/5 dark:hover:bg-white/5 hover:text-[var(--text-main)] font-semibold'
              }`}
            >
              <div className="flex items-center gap-3">
                <Library size={20} />
                <div className="text-left">
                  <span className="block text-sm leading-tight">{t.library}</span>
                  <span className={`text-[11px] font-normal block ${activeTab === 'library' ? 'text-white/80' : 'text-[var(--text-dim)]'}`}>{t.librarySub}</span>
                </div>
              </div>
              {activeTab === 'library' && <ChevronRight size={16} />}
            </button>

            <button
              onClick={() => setActiveTab('backup')}
              className={`w-full flex items-center justify-between p-3 rounded-xl transition-all duration-200 cursor-pointer ${
                activeTab === 'backup'
                  ? 'bg-[var(--accent)] text-white shadow-md font-bold'
                  : 'text-[var(--text-soft)] hover:bg-black/5 dark:hover:bg-white/5 hover:text-[var(--text-main)] font-semibold'
              }`}
            >
              <div className="flex items-center gap-3">
                <Database size={20} />
                <div className="text-left">
                  <span className="block text-sm leading-tight">{t.backup}</span>
                  <span className={`text-[11px] font-normal block ${activeTab === 'backup' ? 'text-white/80' : 'text-[var(--text-dim)]'}`}>{t.backupSub}</span>
                </div>
              </div>
              {activeTab === 'backup' && <ChevronRight size={16} />}
            </button>

            <button
              onClick={() => setActiveTab('watermark')}
              className={`w-full flex items-center justify-between p-3 rounded-xl transition-all duration-200 cursor-pointer ${
                activeTab === 'watermark'
                  ? 'bg-[var(--accent)] text-white shadow-md font-bold'
                  : 'text-[var(--text-soft)] hover:bg-black/5 dark:hover:bg-white/5 hover:text-[var(--text-main)] font-semibold'
              }`}
            >
              <div className="flex items-center gap-3">
                <ShieldCheck size={20} />
                <div className="text-left">
                  <span className="block text-sm leading-tight">{t.watermark}</span>
                  <span className={`text-[11px] font-normal block ${activeTab === 'watermark' ? 'text-white/80' : 'text-[var(--text-dim)]'}`}>{t.watermarkSub}</span>
                </div>
              </div>
              {activeTab === 'watermark' && <ChevronRight size={16} />}
            </button>
          </div>

          <div className="glass-card p-3.5 space-y-2 border border-[var(--border)]">
            <span className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-dim)] block">{t.securityTitle}</span>
            <div className="flex items-center gap-1.5 text-xs text-emerald-600 font-bold">
              <ShieldCheck size={16} /> {t.watermarkActive}
            </div>
            <p className="text-[10px] text-[var(--text-dim)]">{t.watermarkDesc}</p>
          </div>
        </aside>

        {/* MAIN CONTENT AREA */}
        <main className="flex-1 flex overflow-hidden bg-[var(--bg-root)]">
          {activeTab === 'studio' && (
            <div className="flex-1 flex overflow-hidden w-full">
              
              {/* LEFT CONTROL PANEL */}
              {isLeftOpen && (
                <div className="w-[420px] border-r border-[var(--border)] bg-[var(--bg-panel)] p-5 overflow-y-auto space-y-6 shrink-0 transition-all">
                  <div className="flex items-center justify-between pb-1 border-b border-[var(--border)]">
                    <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-dim)] flex items-center gap-2">
                      <Wand2 size={16} className="text-[var(--accent)]" /> {t.configTitle}
                    </span>
                    <button
                      onClick={() => setIsLeftOpen(false)}
                      className="p-1.5 rounded-lg hover:bg-black/5 dark:hover:bg-white/10 text-[var(--text-dim)] hover:text-[var(--text-main)] transition-colors cursor-pointer"
                      title={t.hidePanel}
                    >
                      <PanelLeftClose size={18} />
                    </button>
                  </div>

                  {/* Input Source */}
                  <div className="glass-card p-4 space-y-4">
                    <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-dim)]">{t.pageSource}</span>
                    
                    <div className="grid grid-cols-2 gap-2">
                      <button
                        onClick={() => setInputMode('files')}
                        className={`py-2.5 px-3 rounded-xl text-xs font-bold border transition-all flex items-center justify-center gap-2 cursor-pointer ${
                          inputMode === 'files' ? 'border-[var(--accent)] bg-[var(--accent-glow)] text-[var(--accent)] shadow-sm' : 'border-[var(--border)] text-[var(--text-soft)] hover:border-[var(--border-lit)]'
                        }`}
                      >
                        <ImageIcon size={16} /> {t.pagesMode}
                      </button>

                      <button
                        onClick={() => setInputMode('folder')}
                        className={`py-2.5 px-3 rounded-xl text-xs font-bold border transition-all flex items-center justify-center gap-2 cursor-pointer ${
                          inputMode === 'folder' ? 'border-[var(--accent)] bg-[var(--accent-glow)] text-[var(--accent)] shadow-sm' : 'border-[var(--border)] text-[var(--text-soft)] hover:border-[var(--border-lit)]'
                        }`}
                      >
                        <FolderUp size={16} /> {t.folderMode}
                      </button>
                    </div>

                    {/* CUSTOM STYLED FILE INPUT (Hides browser native 'Elegir archivos') */}
                    {inputMode === 'files' ? (
                      <label className="w-full flex items-center justify-between p-3 rounded-xl border border-[var(--border)] bg-[var(--bg-card)] hover:border-[var(--accent)] cursor-pointer text-xs transition-all shadow-xs">
                        <span className="font-semibold text-[var(--text-soft)] truncate">
                          {inputFiles.length > 0 ? `${inputFiles.length} ${t.filesSelected}` : t.selectPages}
                        </span>
                        <span className="px-3.5 py-1.5 rounded-lg bg-[var(--accent)] text-white font-bold text-xs shrink-0 shadow-xs">
                          {t.browseFiles}
                        </span>
                        <input
                          type="file"
                          multiple
                          accept="image/*"
                          onChange={(e) => handleInputFilesChange(e.target.files)}
                          className="hidden"
                        />
                      </label>
                    ) : (
                      <div className="flex items-center gap-2">
                        <input
                          type="text"
                          placeholder="C:\Folder\Path"
                          value={folderPath}
                          onChange={(e) => setFolderPath(e.target.value)}
                          className="flex-1 px-3.5 py-2.5 rounded-xl text-xs font-mono"
                        />
                        <button
                          onClick={handlePickFolder}
                          className="px-3.5 py-2.5 rounded-xl bg-[var(--accent)] text-white hover:bg-[var(--accent-hover)] text-xs font-bold flex items-center gap-1.5 transition-all cursor-pointer shadow-sm shrink-0"
                          title={t.browseFolder}
                        >
                          <FolderUp size={15} /> {t.browseFolder}
                        </button>
                      </div>
                    )}

                    {/* INPUT QUEUE INSPECTION */}
                    {((inputMode === 'files' && inputFiles.length > 0) || (inputMode === 'folder' && folderScannedImages.length > 0)) && (
                      <div className="space-y-2 pt-2 border-t border-[var(--border)]">
                        <div className="flex items-center justify-between">
                          <span className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-dim)] flex items-center gap-1.5">
                            <ImageIcon size={14} className="text-[var(--accent)]" />
                            {inputMode === 'files' ? `${inputFiles.length} ${t.inputImagesCount}` : `${folderScannedImages.length} ${t.inputFolderCount}`}
                          </span>

                          <div className="flex items-center gap-1 bg-[var(--bg-input)] p-1 rounded-lg border border-[var(--border)]">
                            <button
                              onClick={() => setInputViewMode('list')}
                              className={`p-1 rounded-md transition-all cursor-pointer ${
                                inputViewMode === 'list' ? 'bg-[var(--accent)] text-white shadow-xs' : 'text-[var(--text-soft)] hover:text-[var(--text-main)]'
                              }`}
                              title={t.viewList}
                            >
                              <List size={14} />
                            </button>
                            <button
                              onClick={() => setInputViewMode('grid')}
                              className={`p-1 rounded-md transition-all cursor-pointer ${
                                inputViewMode === 'grid' ? 'bg-[var(--accent)] text-white shadow-xs' : 'text-[var(--text-soft)] hover:text-[var(--text-main)]'
                              }`}
                              title={t.viewGrid}
                            >
                              <LayoutGrid size={14} />
                            </button>
                          </div>
                        </div>

                        {inputViewMode === 'list' && (
                          <div className="max-h-40 overflow-y-auto space-y-1 pr-1 border border-[var(--border)] rounded-xl p-2 bg-[var(--bg-card)]">
                            {inputMode === 'files' ? (
                              Array.from(inputFiles).map((file, idx) => (
                                <div
                                  key={idx}
                                  className="flex items-center justify-between p-2 rounded-lg bg-[var(--bg-panel)] border border-[var(--border)] text-xs group hover:border-[var(--accent)] transition-colors"
                                >
                                  <div className="flex items-center gap-2 overflow-hidden">
                                    <span className="w-5 h-5 rounded-full bg-[var(--accent-glow)] text-[var(--accent)] text-[10px] font-bold flex items-center justify-center shrink-0 font-mono">
                                      {idx + 1}
                                    </span>
                                    <span className="font-mono text-xs truncate text-[var(--text-main)]" title={file.name}>
                                      {file.name}
                                    </span>
                                  </div>
                                  <button
                                    onClick={() => handleRemoveInputFile(idx)}
                                    className="p-1 rounded-md hover:bg-rose-500/10 text-[var(--text-dim)] hover:text-rose-500 transition-colors cursor-pointer"
                                  >
                                    <X size={13} />
                                  </button>
                                </div>
                              ))
                            ) : (
                              folderScannedImages.map((img, idx) => (
                                <div
                                  key={idx}
                                  className="flex items-center justify-between p-2 rounded-lg bg-[var(--bg-panel)] border border-[var(--border)] text-xs hover:border-[var(--accent)] transition-colors"
                                >
                                  <div className="flex items-center gap-2 overflow-hidden">
                                    <span className="w-5 h-5 rounded-full bg-[var(--accent-glow)] text-[var(--accent)] text-[10px] font-bold flex items-center justify-center shrink-0 font-mono">
                                      {idx + 1}
                                    </span>
                                    <span className="font-mono text-xs truncate text-[var(--text-main)]" title={img.name}>
                                      {img.name}
                                    </span>
                                  </div>
                                </div>
                              ))
                            )}
                          </div>
                        )}

                        {inputViewMode === 'grid' && (
                          <div className="max-h-44 overflow-y-auto grid grid-cols-3 gap-2 p-2 border border-[var(--border)] rounded-xl bg-[var(--bg-card)]">
                            {inputMode === 'files' ? (
                              Array.from(inputFiles).map((file, idx) => {
                                const previewUrl = URL.createObjectURL(file);
                                return (
                                  <div
                                    key={idx}
                                    className="relative rounded-lg overflow-hidden border border-[var(--border)] bg-black/20 group h-20"
                                  >
                                    <img src={previewUrl} alt={file.name} className="w-full h-full object-cover" />
                                    <button
                                      onClick={() => handleRemoveInputFile(idx)}
                                      className="absolute top-1 right-1 p-1 rounded-full bg-black/75 text-white opacity-0 group-hover:opacity-100 hover:bg-rose-600 transition-all cursor-pointer"
                                    >
                                      <X size={11} />
                                    </button>
                                  </div>
                                );
                              })
                            ) : (
                              folderScannedImages.map((img, idx) => (
                                <div
                                  key={idx}
                                  className="relative rounded-lg overflow-hidden border border-[var(--border)] bg-black/20 group h-20"
                                >
                                  <img src={img.url} alt={img.name} className="w-full h-full object-cover" />
                                </div>
                              ))
                            )}
                          </div>
                        )}
                      </div>
                    )}

                    <div>
                      <label className="text-xs font-bold text-[var(--text-soft)]">{t.sessionName}</label>
                      <input
                        type="text"
                        value={sessionName}
                        onChange={(e) => setSessionName(e.target.value)}
                        className="w-full px-3 py-2 rounded-xl text-xs mt-1 font-mono"
                      />
                    </div>
                  </div>

                  {/* COLORIZATION MODES */}
                  <div className="glass-card p-4 space-y-3">
                    <div className="flex items-center justify-between">
                      <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-dim)] flex items-center gap-1.5">
                        <Award size={15} className="text-[var(--accent)]" /> {t.colorMode}
                      </span>
                    </div>

                    <div className="space-y-2">
                      {PUBLIC_MODES.map((mode) => {
                        const info = t.modes[mode.id] || {};
                        const IconComp = mode.iconComponent;
                        return (
                          <div
                            key={mode.id}
                            onClick={() => setSelectedModeId(mode.id)}
                            className={`p-3 rounded-2xl border transition-all cursor-pointer flex items-start gap-3 relative ${
                              selectedModeId === mode.id
                                ? 'border-[var(--accent)] bg-[var(--accent-glow)] ring-2 ring-[var(--accent)]/30 shadow-md'
                                : 'border-[var(--border)] hover:border-[var(--border-lit)] bg-[var(--bg-card)]'
                            }`}
                          >
                            <div className={`w-9 h-9 rounded-xl bg-gradient-to-tr ${mode.color} flex items-center justify-center text-white shrink-0 shadow-sm`}>
                              {IconComp ? <IconComp size={18} /> : (mode.icon || '🎨')}
                            </div>

                            <div className="flex-1 min-w-0">
                              <div className="flex items-center justify-between gap-1">
                                <h4 className="text-xs font-bold text-[var(--text-main)] truncate">{info.name}</h4>
                                <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-black/10 dark:bg-white/10 text-[var(--accent)] shrink-0">
                                  {info.badge}
                                </span>
                              </div>
                              <p className="text-[11px] text-[var(--text-dim)] mt-0.5 leading-tight">{info.description}</p>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>

                  {/* COLOR REFERENCE SECTION */}
                  {currentMode.useRef && (
                    <div className="glass-card p-4 space-y-4 border-2 border-[var(--accent)]/50">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold uppercase tracking-wider text-[var(--accent)] flex items-center gap-1.5">
                          🎨 {t.refSection}
                        </span>
                      </div>

                      <div className="space-y-3">
                        <input
                          type="file"
                          accept="image/*"
                          onChange={(e) => handleRefFileChange(e.target.files[0])}
                          className="w-full text-xs text-[var(--text-soft)] file:mr-3 file:py-1.5 file:px-3 file:rounded-xl file:border-0 file:text-xs file:font-semibold file:bg-[var(--accent)] file:text-white hover:file:bg-[var(--accent-hover)] file:cursor-pointer"
                        />

                        {(refPreviewUrl || refPath) && (
                          <div className="relative rounded-2xl overflow-hidden border border-[var(--border)] h-32 bg-black/10 group">
                            <img
                              src={refPreviewUrl || `/api/media?path=${refPath}`}
                              alt="Reference"
                              className="w-full h-full object-contain"
                            />
                            <button
                              onClick={() => { setRefFile(null); setRefPath(''); setRefPreviewUrl(null); }}
                              className="absolute top-2 right-2 p-1.5 rounded-full bg-black/75 text-white hover:bg-rose-600 transition-colors cursor-pointer"
                            >
                              <X size={14} />
                            </button>
                          </div>
                        )}

                        <PaletteExtractor referenceFile={refFile} referencePath={refPath} lang={lang} />

                        <label className="flex items-center gap-2 text-xs text-[var(--text-soft)] cursor-pointer font-semibold pt-1">
                          <input type="checkbox" checked={autoRef} onChange={(e) => setAutoRef(e.target.checked)} className="accent-[var(--accent)]" />
                          🔗 {t.autoRef}
                        </label>
                      </div>
                    </div>
                  )}

                  {/* OUTPUT FORMAT */}
                  <div className="glass-card p-4 space-y-2">
                    <label className="text-xs font-bold uppercase tracking-wider text-[var(--text-dim)] block">{t.outputFormat}</label>
                    <select
                      value={outputFormat}
                      onChange={(e) => setOutputFormat(e.target.value)}
                      className="w-full px-3 py-2 rounded-xl text-xs bg-[var(--bg-input)] border border-[var(--border)] text-[var(--text-main)] font-semibold cursor-pointer"
                    >
                      <option value="png">{t.formatPng}</option>
                      <option value="same_as_input">{t.formatSame}</option>
                      <option value="jpeg">{t.formatJpeg}</option>
                      <option value="webp_lossless">{t.formatWebpLossless}</option>
                      <option value="webp">{t.formatWebp}</option>
                    </select>
                  </div>

                  {/* EXECUTE BUTTON */}
                  <div className="pt-1">
                    {!isGenerating ? (
                      <button
                        onClick={handleStartGenerate}
                        className="w-full py-4 rounded-2xl bg-gradient-to-r from-emerald-600 via-teal-600 to-indigo-600 hover:from-emerald-700 hover:to-indigo-700 text-white font-bold text-sm shadow-xl transition-all flex items-center justify-center gap-2 cursor-pointer"
                      >
                        <Play size={18} fill="white" /> {t.btnStart}
                      </button>
                    ) : (
                      <button
                        onClick={handleCancelGenerate}
                        className="w-full py-4 rounded-2xl bg-rose-600 text-white font-bold text-sm shadow-xl hover:bg-rose-700 transition-all flex items-center justify-center gap-2 cursor-pointer"
                      >
                        <Square size={18} fill="white" /> {t.btnCancel}
                      </button>
                    )}
                  </div>

                </div>
              )}

              {/* CENTRAL CANVAS VISUALIZER */}
              <div className="flex-1 p-6 flex flex-col items-center justify-center overflow-hidden bg-black/5 relative">
                
                {!isLeftOpen && (
                  <button
                    onClick={() => setIsLeftOpen(true)}
                    className="absolute top-4 left-4 z-20 px-3 py-2 rounded-xl glass-card text-[var(--text-soft)] hover:text-[var(--accent)] shadow-md transition-all cursor-pointer flex items-center gap-2 text-xs font-bold"
                  >
                    <PanelLeftOpen size={18} /> {t.showModes}
                  </button>
                )}

                {!isRightOpen && (
                  <button
                    onClick={() => setIsRightOpen(true)}
                    className="absolute top-4 right-4 z-20 px-3 py-2 rounded-xl glass-card text-[var(--text-soft)] hover:text-[var(--accent)] shadow-md transition-all cursor-pointer flex items-center gap-2 text-xs font-bold"
                  >
                    <PanelRightOpen size={18} /> {t.showGallery}
                  </button>
                )}

                <div className="w-full h-full flex flex-col justify-between max-w-6xl space-y-4">
                  
                  {(isGenerating || batchProgress.status) && (
                    <div className="glass-panel p-5 space-y-4 border-2 border-[var(--accent)] animate-fade-in shadow-xl shrink-0 rounded-2xl bg-[var(--bg-panel)]">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-[var(--border)] pb-3">
                        <div className="flex items-center gap-3 overflow-hidden">
                          <span className={`p-2.5 rounded-xl shrink-0 shadow-xs ${
                            isGenerating ? 'bg-[var(--accent-glow)] text-[var(--accent)]' : 'bg-emerald-500/15 text-emerald-600'
                          }`}>
                            {isGenerating ? (
                              <Sparkles size={20} className="animate-spin" />
                            ) : (
                              <CheckCircle2 size={20} />
                            )}
                          </span>
                          <div className="min-w-0">
                            <div className="flex items-center gap-2">
                              <span className="text-[10px] font-extrabold uppercase tracking-wider text-[var(--text-dim)]">
                                {t.sessionName || 'Sesión'}:
                              </span>
                              <span className="text-xs font-mono font-bold text-[var(--accent)] truncate max-w-[200px]">
                                {sessionName}
                              </span>
                            </div>
                            <h4 className="text-sm font-bold text-[var(--text-main)] truncate">
                              {isGenerating
                                ? (batchProgress.stats.current_file ? `${batchProgress.stats.stage || 'Procesando'}: ${batchProgress.stats.current_file}` : (batchProgress.status || t.progressProcessing))
                                : (batchProgress.status || '✨ Generación completada')}
                            </h4>
                          </div>
                        </div>

                        <div className="flex items-center gap-2.5 font-mono text-xs shrink-0 self-end sm:self-auto">
                          <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-[var(--bg-input)] border border-[var(--border)] text-[var(--text-soft)]">
                            <Clock size={14} className="text-[var(--text-dim)]" />
                            <span className="font-bold">{isGenerating ? (batchProgress.stats.elapsed || '00:00') : `Tiempo Total: ${batchProgress.stats.elapsed || '00:00'}`}</span>
                          </div>

                          {isGenerating ? (
                            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-[var(--accent-glow)] border border-[var(--accent)]/30 text-[var(--accent)] font-bold shadow-xs">
                              <Hourglass size={14} />
                              <span>ETA {batchProgress.stats.eta || '--:--'}</span>
                            </div>
                          ) : (
                            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-600 font-bold shadow-xs">
                              <Check size={14} />
                              <span>Completado</span>
                            </div>
                          )}
                        </div>
                      </div>

                      <div className="space-y-1.5">
                        <div className="w-full bg-black/10 dark:bg-white/10 h-3.5 rounded-full overflow-hidden p-0.5 border border-[var(--border)]">
                          <div
                            className={`h-full transition-all duration-300 rounded-full shadow-sm ${
                              isGenerating ? 'bg-gradient-to-r from-emerald-500 via-teal-500 to-indigo-600' : 'bg-gradient-to-r from-emerald-500 to-teal-500'
                            }`}
                            style={{ width: `${isGenerating ? (batchProgress.stats.percent || 0) : 100}%` }}
                          />
                        </div>

                        <div className="flex items-center justify-between text-xs font-mono font-semibold text-[var(--text-soft)]">
                          <span>{t.pageOf || 'Página'} {batchProgress.stats.done || 0} / {batchProgress.stats.total || 0}</span>
                          <span className="text-[var(--accent)] font-bold">{isGenerating ? (batchProgress.stats.percent || 0) : 100}%</span>
                        </div>
                      </div>
                    </div>
                  )}

                  <div className="flex items-center justify-between shrink-0">
                    <div>
                      <h2 className="font-bold text-xl text-[var(--text-main)]">{t.visualizerTitle} {currentModeInfo.name}</h2>
                      <p className="text-xs text-[var(--text-dim)] font-medium">{t.visualizerDesc}</p>
                    </div>
                  </div>

                  <BeforeAfterSlider
                    beforeImage={selectedGalleryItem ? (selectedGalleryItem.original_url || currentOriginalUrl) : currentOriginalUrl}
                    afterImage={selectedGalleryItem ? selectedGalleryItem.url : null}
                    lang={lang}
                  />
                </div>
              </div>

              {/* RIGHT DRAWER (GALLERY) */}
              {isRightOpen && (
                <div className="w-80 border-l border-[var(--border)] bg-[var(--bg-panel)] p-4 overflow-y-auto space-y-6 shrink-0 transition-all">
                  <div className="flex items-center justify-between pb-1 border-b border-[var(--border)]">
                    <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-dim)] flex items-center gap-2">
                      <ImageIcon size={16} className="text-[var(--accent)]" /> {t.galleryTitle} ({gallery.length})
                    </span>
                    <button
                      onClick={() => setIsRightOpen(false)}
                      className="p-1.5 rounded-lg hover:bg-black/5 dark:hover:bg-white/10 text-[var(--text-dim)] hover:text-[var(--text-main)] transition-colors cursor-pointer"
                      title={t.hidePanel}
                    >
                      <PanelRightClose size={18} />
                    </button>
                  </div>

                  <div className="grid grid-cols-2 gap-2.5 max-h-[75vh] overflow-y-auto p-1">
                    {gallery.map((item, idx) => (
                      <div
                        key={idx}
                        onClick={() => {
                          setSelectedGalleryItem(item);
                          if (item.original_url) {
                            setCurrentOriginalUrl(item.original_url);
                          }
                        }}
                        className={`glass-card overflow-hidden cursor-pointer border transition-all group relative flex flex-col ${
                          selectedGalleryItem?.url === item.url ? 'border-[var(--accent)] ring-2 ring-[var(--accent)]/40 scale-105' : 'hover:border-[var(--border-lit)]'
                        }`}
                      >
                        <img src={item.url} alt={item.name} className="w-full h-28 object-cover" />
                        <div className="p-1.5 text-center bg-black/60 backdrop-blur-xs">
                          <span className="text-[10px] font-mono font-semibold truncate block text-white">{item.name}</span>
                        </div>
                        
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setRefPath(item.full_path);
                            setRefFile(null);
                            setRefPreviewUrl(item.url);
                            addToast(t.toastRefPinned, 'success');
                          }}
                          title={t.useAsRef}
                          className="absolute top-1.5 right-1.5 p-1.5 rounded-lg bg-black/75 text-white opacity-0 group-hover:opacity-100 hover:bg-[var(--accent)] transition-all cursor-pointer"
                        >
                          <Wand2 size={13} />
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}

            </div>
          )}

          {activeTab === 'reader' && <div className="flex-1 p-6 overflow-y-auto w-full h-full"><MangaReaderView onToast={addToast} lang={lang} /></div>}
          {activeTab === 'library' && <div className="flex-1 p-6 overflow-y-auto w-full h-full"><LibraryView onToast={addToast} lang={lang} /></div>}
          {activeTab === 'backup' && <div className="flex-1 p-6 overflow-y-auto w-full h-full"><BackupView onToast={addToast} lang={lang} /></div>}
          {activeTab === 'watermark' && <div className="flex-1 p-6 overflow-y-auto w-full h-full"><WatermarkView onToast={addToast} lang={lang} /></div>}
        </main>
      </div>

      {/* TOAST NOTIFICATIONS */}
      <div className="fixed bottom-6 right-6 z-50 flex flex-col gap-3 pointer-events-none">
        {toasts.map((tItem) => (
          <div
            key={tItem.id}
            className={`px-5 py-3.5 rounded-2xl text-xs font-bold text-white shadow-2xl pointer-events-auto flex items-center gap-3 animate-fade-in ${
              tItem.type === 'error' ? 'bg-rose-600' : tItem.type === 'warning' ? 'bg-amber-600' : 'bg-emerald-600'
            }`}
          >
            {tItem.message}
          </div>
        ))}
      </div>

    </div>
  );
}
