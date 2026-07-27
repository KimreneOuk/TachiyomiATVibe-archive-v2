import React, { useState, useEffect } from 'react';
import { History, Star, Archive, RefreshCw, Layers, HardDrive, FileImage, Search, Filter } from 'lucide-react';
import { TRANSLATIONS } from '../i18n';

export default function LibraryView({ onToast, lang = 'en' }) {
  const [historyData, setHistoryData] = useState([]);
  const [favoritesData, setFavoritesData] = useState([]);
  const [libraryGallery, setLibraryGallery] = useState([]);
  const [selectedSessionId, setSelectedSessionId] = useState('');
  const [activeTab, setActiveTab] = useState('history');
  const [searchTerm, setSearchTerm] = useState('');
  const [loading, setLoading] = useState(false);

  const t = TRANSLATIONS[lang] || TRANSLATIONS.en;

  const fetchLibrary = async () => {
    setLoading(true);
    try {
      const resp = await fetch('/api/history');
      if (resp.ok) {
        const data = await resp.json();
        setHistoryData(data.history || []);
        setFavoritesData(data.favorites || []);
        setLibraryGallery(data.library_gallery || []);
      }
    } catch (err) {
      onToast('Error loading library', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLibrary();
  }, []);

  const handleToggleFavorite = async (sessionId) => {
    const targetId = sessionId || selectedSessionId;
    if (!targetId) {
      onToast('Select a session from history', 'warning');
      return;
    }

    const formData = new FormData();
    formData.append('session_id', targetId);

    try {
      const resp = await fetch('/api/history/toggle-fav', {
        method: 'POST',
        body: formData,
      });
      if (resp.ok) {
        const data = await resp.json();
        onToast(data.message, 'success');
        fetchLibrary();
      }
    } catch (err) {
      onToast('Error toggling favorite', 'error');
    }
  };

  const handleExportCbz = async (sessionId) => {
    const targetId = sessionId || selectedSessionId;
    if (!targetId) {
      onToast('Select a session from history', 'warning');
      return;
    }

    const formData = new FormData();
    formData.append('session_id', targetId);

    try {
      const resp = await fetch('/api/history/export-cbz', {
        method: 'POST',
        body: formData,
      });
      if (resp.ok) {
        const data = await resp.json();
        onToast(data.message, 'success');
      } else {
        const err = await resp.json();
        onToast(err.detail || 'Error exporting CBZ', 'error');
      }
    } catch (err) {
      onToast('Network error exporting CBZ', 'error');
    }
  };

  const totalPagesCount = historyData.reduce((acc, h) => acc + (h.num_images || 0), 0);
  const filteredHistory = historyData.filter((h) => 
    (h.session_name || '').toLowerCase().includes(searchTerm.toLowerCase()) ||
    (h.date_display || '').toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <div className="w-full h-full flex flex-col space-y-6 animate-fade-in p-2">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="glass-panel p-5 flex items-center justify-between">
          <div>
            <span className="text-xs text-[var(--text-dim)] font-semibold uppercase tracking-wider block">{t.processedSessions}</span>
            <h3 className="text-3xl font-bold text-[var(--text-main)] mt-1">{historyData.length}</h3>
          </div>
          <div className="w-12 h-12 rounded-2xl bg-blue-500/10 text-blue-600 flex items-center justify-center font-bold">
            <History size={24} />
          </div>
        </div>

        <div className="glass-panel p-5 flex items-center justify-between">
          <div>
            <span className="text-xs text-[var(--text-dim)] font-semibold uppercase tracking-wider block">{t.favChapters}</span>
            <h3 className="text-3xl font-bold text-[var(--accent)] mt-1">{favoritesData.length}</h3>
          </div>
          <div className="w-12 h-12 rounded-2xl bg-amber-500/10 text-amber-600 flex items-center justify-center font-bold">
            <Star size={24} />
          </div>
        </div>

        <div className="glass-panel p-5 flex items-center justify-between">
          <div>
            <span className="text-xs text-[var(--text-dim)] font-semibold uppercase tracking-wider block">{t.coloredPages}</span>
            <h3 className="text-3xl font-bold text-emerald-600 mt-1">{totalPagesCount}</h3>
          </div>
          <div className="w-12 h-12 rounded-2xl bg-emerald-500/10 text-emerald-600 flex items-center justify-center font-bold">
            <FileImage size={24} />
          </div>
        </div>

        <div className="glass-panel p-5 flex items-center justify-between">
          <div>
            <span className="text-xs text-[var(--text-dim)] font-semibold uppercase tracking-wider block">{t.outputStorage}</span>
            <h3 className="text-xl font-bold text-[var(--text-main)] mt-1">Colored Manga/</h3>
          </div>
          <div className="w-12 h-12 rounded-2xl bg-indigo-500/10 text-indigo-600 flex items-center justify-center font-bold">
            <HardDrive size={24} />
          </div>
        </div>
      </div>

      <div className="glass-panel p-4 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <button
            onClick={() => setActiveTab('history')}
            className={`flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all cursor-pointer ${
              activeTab === 'history' ? 'bg-[var(--accent)] text-white shadow-md' : 'text-[var(--text-soft)] hover:bg-black/5'
            }`}
          >
            <History size={16} /> {t.historyTab} ({historyData.length})
          </button>

          <button
            onClick={() => setActiveTab('favorites')}
            className={`flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all cursor-pointer ${
              activeTab === 'favorites' ? 'bg-[var(--accent)] text-white shadow-md' : 'text-[var(--text-soft)] hover:bg-black/5'
            }`}
          >
            <Star size={16} /> {t.favoritesTab} ({favoritesData.length})
          </button>
        </div>

        <div className="flex items-center gap-3">
          <div className="relative">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-dim)]" />
            <input
              type="text"
              placeholder={t.searchPlaceholder}
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="pl-9 pr-4 py-2 rounded-xl text-xs bg-[var(--bg-input)] border border-[var(--border)] w-48 focus:w-64 transition-all"
            />
          </div>

          <button
            onClick={fetchLibrary}
            disabled={loading}
            className="p-2.5 rounded-xl border border-[var(--border)] text-[var(--text-dim)] hover:text-[var(--accent)] transition-colors cursor-pointer"
            title={t.refreshLibrary}
          >
            <RefreshCw size={16} className={loading ? 'animate-spin' : ''} />
          </button>
        </div>
      </div>

      {activeTab === 'history' && (
        <div className="glass-panel p-5 flex-1 overflow-hidden flex flex-col min-h-[450px]">
          <div className="overflow-auto flex-1">
            <table className="w-full text-left border-collapse text-sm">
              <thead>
                <tr className="border-b border-[var(--border)] text-[var(--text-dim)] font-bold text-xs uppercase tracking-wider bg-[var(--bg-card)]">
                  <th className="p-3.5">{t.colStar}</th>
                  <th className="p-3.5">{t.colDate}</th>
                  <th className="p-3.5">{t.colName}</th>
                  <th className="p-3.5">{t.colSource}</th>
                  <th className="p-3.5">{t.colPages}</th>
                  <th className="p-3.5">{t.colStatus}</th>
                  <th className="p-3.5 text-right">{t.colActions}</th>
                </tr>
              </thead>
              <tbody>
                {filteredHistory.length > 0 ? (
                  filteredHistory.map((h) => {
                    const isFav = favoritesData.includes(h.id);
                    return (
                      <tr
                        key={h.id}
                        onClick={() => setSelectedSessionId(h.id)}
                        className={`border-b border-[var(--border)]/60 hover:bg-black/5 dark:hover:bg-white/5 cursor-pointer transition-colors ${
                          selectedSessionId === h.id ? 'bg-[var(--accent-glow)]' : ''
                        }`}
                      >
                        <td className="p-3.5 text-lg">{isFav ? '⭐' : '☆'}</td>
                        <td className="p-3.5 font-mono text-xs text-[var(--text-soft)]">{h.date_display}</td>
                        <td className="p-3.5 font-bold text-[var(--text-main)]">{h.session_name}</td>
                        <td className="p-3.5 text-xs text-[var(--text-dim)] max-w-xs truncate">{h.input_source}</td>
                        <td className="p-3.5 font-mono font-bold text-[var(--accent)]">{h.num_images}</td>
                        <td className="p-3.5">
                          <span className={`px-3 py-1 rounded-full text-xs font-bold ${
                            h.status === 'completado' ? 'bg-emerald-500/10 text-emerald-600' : 'bg-rose-500/10 text-rose-600'
                          }`}>
                            {h.status}
                          </span>
                        </td>
                        <td className="p-3.5 text-right">
                          <div className="flex items-center justify-end gap-2" onClick={(e) => e.stopPropagation()}>
                            <button
                              onClick={() => handleToggleFavorite(h.id)}
                              className="px-3 py-1.5 rounded-lg bg-[var(--bg-card)] border border-[var(--border)] hover:border-amber-500 hover:text-amber-500 text-xs font-semibold flex items-center gap-1 transition-all"
                            >
                              <Star size={14} fill={isFav ? 'currentColor' : 'none'} /> {isFav ? t.removeFav : t.addFav}
                            </button>
                            
                            <button
                              onClick={() => handleExportCbz(h.id)}
                              className="px-3 py-1.5 rounded-lg bg-[var(--accent)] text-white hover:bg-[var(--accent-hover)] text-xs font-semibold flex items-center gap-1 transition-all shadow-sm"
                            >
                              <Archive size={14} /> {t.exportCbzBtn}
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })
                ) : (
                  <tr>
                    <td colSpan={7} className="p-12 text-center text-[var(--text-dim)]">
                      {t.noHistory}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {activeTab === 'favorites' && (
        <div className="glass-panel p-6 flex-1 overflow-y-auto min-h-[450px]">
          <h4 className="font-bold text-base text-[var(--text-main)] mb-4">{t.libraryCovers}</h4>
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-5">
            {libraryGallery.map((item, idx) => (
              <div key={idx} className="glass-card overflow-hidden group border border-[var(--border)] flex flex-col justify-between hover:border-[var(--accent)] transition-all">
                <div className="h-56 overflow-hidden relative bg-black/10">
                  <img
                    src={`/api/media?path=${item[0]}`}
                    alt={item[1]}
                    className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                  />
                </div>
                <div className="p-3 bg-[var(--bg-card)] border-t border-[var(--border)]">
                  <p className="font-bold text-xs text-[var(--text-main)] truncate">{item[1]}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

    </div>
  );
}
