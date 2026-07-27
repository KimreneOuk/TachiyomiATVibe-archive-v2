import React, { useState, useEffect } from 'react';
import { FolderOpen, Archive, ChevronLeft, ChevronRight, Layers, ScrollText, BookOpen, FileText, Maximize2, Minimize2, ZoomIn, ZoomOut, RotateCcw } from 'lucide-react';
import { TRANSLATIONS } from '../i18n';

export default function MangaReaderView({ onToast, lang = 'en' }) {
  const [sourceMode, setSourceMode] = useState('folder');
  const [folderPath, setFolderPath] = useState('');
  const [cbzFile, setCbzFile] = useState(null);

  const [readerData, setReaderData] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [viewMode, setViewMode] = useState('single');
  const [zoomLevel, setZoomLevel] = useState(100);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [loading, setLoading] = useState(false);

  const t = TRANSLATIONS[lang] || TRANSLATIONS.en;

  const handleLoadReader = async () => {
    setLoading(true);
    try {
      const formData = new FormData();
      formData.append('mode', sourceMode);

      if (sourceMode === 'folder') {
        if (!folderPath.trim()) {
          onToast(t.toastReqFolder, 'warning');
          setLoading(false);
          return;
        }
        formData.append('folder_path', folderPath.trim());
      } else {
        if (!cbzFile) {
          onToast('Select a .cbz file', 'warning');
          setLoading(false);
          return;
        }
        formData.append('cbz_file', cbzFile);
      }

      const resp = await fetch('/api/reader/load', {
        method: 'POST',
        body: formData,
      });

      if (resp.ok) {
        const data = await resp.json();
        setReaderData(data);
        setCurrentPage(1);
        onToast(data.message, 'success');
      } else {
        const err = await resp.json();
        onToast(err.detail || 'Error loading manga', 'error');
      }
    } catch (err) {
      onToast('Network error loading manga', 'error');
    } finally {
      setLoading(false);
    }
  };

  const totalPages = readerData?.total_pages || 0;
  const pages = readerData?.pages || [];

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (!readerData) return;
      if (e.key === 'ArrowRight' || e.key === 'PageDown') {
        setCurrentPage((p) => Math.min(totalPages, p + (viewMode === 'double' ? 2 : 1)));
      } else if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
        setCurrentPage((p) => Math.max(1, p - (viewMode === 'double' ? 2 : 1)));
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [readerData, totalPages, viewMode]);

  return (
    <div className={`w-full h-full flex flex-col space-y-4 animate-fade-in p-2 ${isFullscreen ? 'fixed inset-0 z-50 bg-[var(--bg-root)] p-6' : ''}`}>
      
      <div className="glass-panel p-5 flex flex-col lg:flex-row lg:items-center justify-between gap-4">
        <div className="flex flex-col sm:flex-row sm:items-center gap-4 flex-1">
          <div className="flex items-center gap-2 font-bold text-base text-[var(--accent)] shrink-0">
            <BookOpen size={22} /> {t.readerTitle}
          </div>

          <div className="flex items-center gap-3 bg-[var(--bg-input)] p-1.5 rounded-xl border border-[var(--border)] shrink-0">
            <label className={`flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg cursor-pointer transition-all ${
              sourceMode === 'folder' ? 'bg-[var(--accent)] text-white shadow-sm' : 'text-[var(--text-soft)]'
            }`}>
              <input type="radio" name="srcMode" checked={sourceMode === 'folder'} onChange={() => setSourceMode('folder')} className="hidden" />
              <FolderOpen size={15} /> {t.sourceFolder}
            </label>

            <label className={`flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg cursor-pointer transition-all ${
              sourceMode === 'cbz' ? 'bg-[var(--accent)] text-white shadow-sm' : 'text-[var(--text-soft)]'
            }`}>
              <input type="radio" name="srcMode" checked={sourceMode === 'cbz'} onChange={() => setSourceMode('cbz')} className="hidden" />
              <Archive size={15} /> {t.sourceCbz}
            </label>
          </div>

          {sourceMode === 'folder' ? (
            <div className="flex items-center gap-2 flex-1 min-w-[280px]">
              <input
                type="text"
                placeholder={t.folderPlaceholder}
                value={folderPath}
                onChange={(e) => setFolderPath(e.target.value)}
                className="flex-1 px-3 py-2 rounded-xl text-sm font-mono"
              />
              <button
                onClick={async () => {
                  try {
                    const resp = await fetch('/api/pick_folder');
                    if (resp.ok) {
                      const data = await resp.json();
                      if (data.folder_path) setFolderPath(data.folder_path);
                    }
                  } catch (err) {}
                }}
                className="px-3.5 py-2 rounded-xl bg-[var(--bg-input)] border border-[var(--border)] hover:border-[var(--accent)] hover:text-[var(--accent)] text-xs font-bold flex items-center gap-1.5 transition-all cursor-pointer shrink-0"
                title={t.browseFolder}
              >
                <FolderOpen size={15} /> {t.browseFolder}
              </button>
              <button
                onClick={handleLoadReader}
                disabled={loading}
                className="px-5 py-2 rounded-xl bg-[var(--accent)] text-white font-bold text-xs hover:bg-[var(--accent-hover)] transition-all whitespace-nowrap cursor-pointer shadow-sm"
              >
                {loading ? t.loadingBtn : t.loadFolderBtn}
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-2 flex-1 min-w-[280px]">
              <input
                type="file"
                accept=".cbz,.zip"
                onChange={(e) => setCbzFile(e.target.files[0])}
                className="flex-1 text-xs text-[var(--text-soft)] file:mr-3 file:py-1.5 file:px-3 file:rounded-lg file:border-0 file:text-xs file:font-semibold file:bg-[var(--accent)] file:text-white hover:file:bg-[var(--accent-hover)] file:cursor-pointer"
              />
              <button
                onClick={handleLoadReader}
                disabled={loading}
                className="px-5 py-2 rounded-xl bg-[var(--accent)] text-white font-bold text-xs hover:bg-[var(--accent-hover)] transition-all whitespace-nowrap cursor-pointer shadow-sm"
              >
                {loading ? t.loadingBtn : t.loadCbzBtn}
              </button>
            </div>
          )}
        </div>

        {readerData && (
          <div className="flex items-center gap-2 shrink-0 border-t lg:border-t-0 pt-3 lg:pt-0 border-[var(--border)]">
            <div className="flex items-center gap-1 bg-[var(--bg-input)] p-1.5 rounded-xl border border-[var(--border)]">
              <button
                onClick={() => setViewMode('single')}
                className={`flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg font-semibold transition-all cursor-pointer ${
                  viewMode === 'single' ? 'bg-[var(--accent)] text-white shadow-sm' : 'text-[var(--text-soft)] hover:text-[var(--text-main)]'
                }`}
                title={t.singleMode}
              >
                <FileText size={15} /> {t.singleMode}
              </button>

              <button
                onClick={() => setViewMode('double')}
                className={`flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg font-semibold transition-all cursor-pointer ${
                  viewMode === 'double' ? 'bg-[var(--accent)] text-white shadow-sm' : 'text-[var(--text-soft)] hover:text-[var(--text-main)]'
                }`}
                title={t.doubleMode}
              >
                <Layers size={15} /> {t.doubleMode}
              </button>

              <button
                onClick={() => setViewMode('webtoon')}
                className={`flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg font-semibold transition-all cursor-pointer ${
                  viewMode === 'webtoon' ? 'bg-[var(--accent)] text-white shadow-sm' : 'text-[var(--text-soft)] hover:text-[var(--text-main)]'
                }`}
                title={t.webtoonMode}
              >
                <ScrollText size={15} /> {t.webtoonMode}
              </button>
            </div>

            <button
              onClick={() => setIsFullscreen(!isFullscreen)}
              className="p-2.5 rounded-xl glass-card text-[var(--text-soft)] hover:text-[var(--accent)] transition-all cursor-pointer"
              title={isFullscreen ? t.exitFullscreen : t.fullscreen}
            >
              {isFullscreen ? <Minimize2 size={18} /> : <Maximize2 size={18} />}
            </button>
          </div>
        )}

      </div>

      {readerData ? (
        <div className="glass-panel flex-1 flex flex-col items-center justify-between p-4 overflow-hidden relative min-h-[500px]">
          <div className="w-full flex items-center justify-between text-xs text-[var(--text-soft)] border-b border-[var(--border)] pb-2 mb-2 font-medium">
            <span className="font-semibold text-[var(--text-main)]">{readerData.source_name || 'Loaded Manga'}</span>
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-1">
                <button onClick={() => setZoomLevel((z) => Math.max(50, z - 10))} className="p-1 hover:text-[var(--accent)]"><ZoomOut size={14} /></button>
                <span className="font-mono text-xs">{zoomLevel}%</span>
                <button onClick={() => setZoomLevel((z) => Math.min(200, z + 10))} className="p-1 hover:text-[var(--accent)]"><ZoomIn size={14} /></button>
                <button onClick={() => setZoomLevel(100)} className="p-1 hover:text-[var(--accent)]" title={t.resetZoom}><RotateCcw size={13} /></button>
              </div>
              <span className="font-mono bg-[var(--accent-glow)] text-[var(--accent)] px-2.5 py-0.5 rounded-full font-bold">
                {t.pageOf} {currentPage} / {totalPages}
              </span>
            </div>
          </div>

          <div className="flex-1 w-full flex items-center justify-center overflow-auto p-2">
            {viewMode === 'single' && (
              <div className="flex items-center justify-center h-full w-full">
                <img
                  src={pages[currentPage - 1]}
                  alt={`Page ${currentPage}`}
                  style={{ transform: `scale(${zoomLevel / 100})`, transformOrigin: 'center' }}
                  className="max-h-[75vh] w-auto object-contain rounded-xl shadow-2xl border border-[var(--border)] transition-transform duration-150"
                />
              </div>
            )}

            {viewMode === 'double' && (
              <div className="flex items-center justify-center gap-3 h-full w-full">
                {pages[currentPage - 1] && (
                  <img
                    src={pages[currentPage - 1]}
                    alt={`Page ${currentPage}`}
                    style={{ transform: `scale(${zoomLevel / 100})`, transformOrigin: 'center' }}
                    className="max-h-[75vh] w-auto object-contain rounded-xl shadow-2xl border border-[var(--border)] transition-transform duration-150"
                  />
                )}
                {pages[currentPage] && (
                  <img
                    src={pages[currentPage]}
                    alt={`Page ${currentPage + 1}`}
                    style={{ transform: `scale(${zoomLevel / 100})`, transformOrigin: 'center' }}
                    className="max-h-[75vh] w-auto object-contain rounded-xl shadow-2xl border border-[var(--border)] transition-transform duration-150"
                  />
                )}
              </div>
            )}

            {viewMode === 'webtoon' && (
              <div className="w-full max-w-3xl flex flex-col items-center gap-1 py-4 overflow-y-auto max-h-[75vh]">
                {pages.map((imgUrl, idx) => (
                  <img key={idx} src={imgUrl} alt={`Page ${idx + 1}`} loading="lazy" className="w-full object-contain rounded-lg shadow-md" />
                ))}
              </div>
            )}
          </div>

          {viewMode !== 'webtoon' && (
            <div className="w-full max-w-2xl flex items-center justify-between gap-4 bg-[var(--bg-card)] p-3 rounded-2xl border border-[var(--border)] shadow-md mt-2">
              <button
                disabled={currentPage <= 1}
                onClick={() => setCurrentPage((p) => Math.max(1, p - (viewMode === 'double' ? 2 : 1)))}
                className="px-4 py-2 rounded-xl bg-[var(--bg-input)] hover:bg-[var(--accent)] hover:text-white disabled:opacity-30 transition-all font-semibold text-xs flex items-center gap-1 cursor-pointer"
              >
                <ChevronLeft size={18} /> {t.prevBtn}
              </button>

              <div className="flex-1 flex items-center gap-3">
                <input
                  type="range"
                  min={1}
                  max={totalPages}
                  value={currentPage}
                  onChange={(e) => setCurrentPage(Number(e.target.value))}
                  className="w-full accent-[var(--accent)]"
                />
                <span className="font-mono text-xs font-bold text-[var(--accent)] whitespace-nowrap min-w-[60px] text-right">
                  {currentPage} / {totalPages}
                </span>
              </div>

              <button
                disabled={currentPage >= totalPages}
                onClick={() => setCurrentPage((p) => Math.min(totalPages, p + (viewMode === 'double' ? 2 : 1)))}
                className="px-4 py-2 rounded-xl bg-[var(--bg-input)] hover:bg-[var(--accent)] hover:text-white disabled:opacity-30 transition-all font-semibold text-xs flex items-center gap-1 cursor-pointer"
              >
                {t.nextBtn} <ChevronRight size={18} />
              </button>
            </div>
          )}

        </div>
      ) : (
        <div className="glass-panel flex-1 min-h-[500px] flex flex-col items-center justify-center text-center p-8 space-y-4">
          <div className="w-20 h-20 rounded-3xl bg-[var(--accent-glow)] flex items-center justify-center text-[var(--accent)]">
            <BookOpen size={42} />
          </div>
          <div>
            <h3 className="text-xl font-bold text-[var(--text-main)] mb-1">{t.emptyReaderTitle}</h3>
            <p className="text-xs text-[var(--text-soft)] max-w-md">{t.emptyReaderDesc}</p>
          </div>
        </div>
      )}

    </div>
  );
}
