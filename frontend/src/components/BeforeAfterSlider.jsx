import React, { useState, useRef, useCallback, useEffect } from 'react';
import { MoveHorizontal, Download, Maximize2, Minimize2, RotateCcw, Copy, Check, Sparkles } from 'lucide-react';
import { TRANSLATIONS } from '../i18n';

export default function BeforeAfterSlider({ beforeImage, afterImage, lang = 'en', alt = "Comparison" }) {
  const [sliderPosition, setSliderPosition] = useState(50);
  const [isDragging, setIsDragging] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [copied, setCopied] = useState(false);
  const containerRef = useRef(null);

  const t = TRANSLATIONS[lang] || TRANSLATIONS.en;

  const handleMove = useCallback((clientX) => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const x = clientX - rect.left;
    let position = (x / rect.width) * 100;
    if (position < 0) position = 0;
    if (position > 100) position = 100;
    setSliderPosition(position);
  }, []);

  const handleTouchMove = useCallback((e) => {
    if (!isDragging) return;
    handleMove(e.touches[0].clientX);
  }, [isDragging, handleMove]);

  const handleMouseMove = useCallback((e) => {
    if (!isDragging) return;
    handleMove(e.clientX);
  }, [isDragging, handleMove]);

  const handleMouseUp = useCallback(() => {
    setIsDragging(false);
  }, []);

  useEffect(() => {
    if (isDragging) {
      window.addEventListener('mousemove', handleMouseMove);
      window.addEventListener('mouseup', handleMouseUp);
      window.addEventListener('touchmove', handleTouchMove);
      window.addEventListener('touchend', handleMouseUp);
    }
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
      window.removeEventListener('touchmove', handleTouchMove);
      window.removeEventListener('touchend', handleMouseUp);
    };
  }, [isDragging, handleMouseMove, handleMouseUp, handleTouchMove]);

  const handleDownload = () => {
    if (!afterImage) return;
    const a = document.createElement('a');
    a.href = afterImage;
    a.download = `manga_colored_${Date.now()}.png`;
    a.click();
  };

  const handleCopyImage = async () => {
    if (!afterImage) return;
    try {
      const resp = await fetch(afterImage);
      const blob = await resp.blob();
      await navigator.clipboard.write([new ClipboardItem({ [blob.type]: blob })]);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Error copying to clipboard:', err);
    }
  };

  if (!afterImage) {
    return (
      <div className="w-full flex-1 min-h-[500px] rounded-3xl flex flex-col items-center justify-center border-2 border-dashed border-[var(--border)] text-[var(--text-dim)] bg-[var(--bg-card)] p-8 text-center space-y-3">
        <div className="w-16 h-16 rounded-2xl bg-[var(--bg-input)] flex items-center justify-center text-[var(--accent)] border border-[var(--border)] shadow-inner">
          <Sparkles size={28} />
        </div>
        <div>
          <h3 className="font-semibold text-lg text-[var(--text-main)] mb-1">{t.emptyCanvasTitle}</h3>
          <p className="text-xs text-[var(--text-soft)] max-w-sm">{t.emptyCanvasDesc} <strong className="text-[var(--accent)]">{t.btnStart}</strong></p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col w-full space-y-3 min-h-[500px]">
      <div 
        ref={containerRef}
        className={`slider-container w-full flex-1 border border-[var(--border)] bg-black/40 relative overflow-hidden cursor-crosshair select-none transition-all duration-300 ${
          isFullscreen 
            ? 'fixed inset-4 z-50 h-[calc(100vh-2rem)] rounded-3xl shadow-2xl backdrop-blur-2xl' 
            : 'min-h-[480px] rounded-3xl shadow-xl'
        }`}
        onMouseDown={(e) => { setIsDragging(true); handleMove(e.clientX); }}
        onTouchStart={(e) => { setIsDragging(true); handleMove(e.touches[0].clientX); }}
      >
        <img
          src={afterImage}
          alt="Colored"
          className="absolute inset-0 w-full h-full object-contain pointer-events-none p-2"
        />

        {beforeImage && (
          <img
            src={beforeImage}
            alt="Original B/W"
            className="absolute inset-0 w-full h-full object-contain pointer-events-none p-2 transition-none"
            style={{ clipPath: `inset(0 ${100 - sliderPosition}% 0 0)` }}
          />
        )}

        {beforeImage && (
          <div
            className="slider-handle"
            style={{ left: `${sliderPosition}%` }}
          >
            <div className="slider-handle-knob">
              <MoveHorizontal size={18} />
            </div>
          </div>
        )}

        {beforeImage && (
          <span className="absolute top-4 left-4 bg-black/75 text-white text-[11px] font-mono px-3.5 py-1.5 rounded-full backdrop-blur-md pointer-events-none font-semibold border border-white/10 shadow-md">
            {t.origBn}
          </span>
        )}
        <span className="absolute top-4 right-4 bg-gradient-to-r from-[var(--accent)] to-[var(--accent-hover)] text-white text-[11px] font-mono px-3.5 py-1.5 rounded-full backdrop-blur-md pointer-events-none font-bold shadow-lg">
          {t.colorAi}
        </span>
      </div>

      <div className="flex items-center justify-between px-2 text-xs text-[var(--text-soft)]">
        <div className="flex items-center gap-2">
          {beforeImage && (
            <button
              onClick={() => setSliderPosition(50)}
              className="px-3 py-1.5 rounded-xl glass-card text-[var(--text-soft)] hover:text-[var(--accent)] transition-all flex items-center gap-1.5 font-medium cursor-pointer"
              title={t.centerSlider}
            >
              <RotateCcw size={13} /> 50%
            </button>
          )}

          <button
            onClick={handleCopyImage}
            className="px-3 py-1.5 rounded-xl glass-card text-[var(--text-soft)] hover:text-[var(--accent)] transition-all flex items-center gap-1.5 font-medium cursor-pointer"
            title={t.copyImg}
          >
            {copied ? <Check size={13} className="text-emerald-400" /> : <Copy size={13} />}
            {copied ? t.copiedImg : t.copyImg}
          </button>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleDownload}
            className="px-3.5 py-1.5 rounded-xl bg-[var(--accent)] text-white font-bold text-xs hover:bg-[var(--accent-hover)] transition-all flex items-center gap-1.5 shadow-md cursor-pointer"
            title={t.savePng}
          >
            <Download size={14} /> {t.savePng}
          </button>

          <button
            onClick={() => setIsFullscreen(!isFullscreen)}
            className="p-2 rounded-xl glass-card text-[var(--text-soft)] hover:text-[var(--accent)] transition-all cursor-pointer"
            title={isFullscreen ? t.exitFullscreen : t.fullscreen}
          >
            {isFullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
          </button>
        </div>
      </div>
    </div>
  );
}
