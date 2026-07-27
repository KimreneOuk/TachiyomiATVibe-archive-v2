import React, { useState, useEffect } from 'react';
import { Palette, Copy, Check } from 'lucide-react';
import { TRANSLATIONS } from '../i18n';

export default function PaletteExtractor({ referencePath, referenceFile, lang = 'en' }) {
  const [colors, setColors] = useState([]);
  const [copiedIndex, setCopiedIndex] = useState(null);
  const [loading, setLoading] = useState(false);

  const t = TRANSLATIONS[lang] || TRANSLATIONS.en;

  useEffect(() => {
    if (!referencePath && !referenceFile) {
      setColors([]);
      return;
    }

    const fetchPalette = async () => {
      setLoading(true);
      try {
        const formData = new FormData();
        if (referenceFile) {
          formData.append('file', referenceFile);
        } else if (referencePath) {
          formData.append('image_path', referencePath);
        }

        const resp = await fetch('/api/extract-palette', {
          method: 'POST',
          body: formData,
        });

        if (resp.ok) {
          const data = await resp.json();
          setColors(data.colors || []);
        }
      } catch (err) {
        console.error('Error extracting palette:', err);
      } finally {
        setLoading(false);
      }
    };

    fetchPalette();
  }, [referencePath, referenceFile]);

  const copyToClipboard = (hex, idx) => {
    navigator.clipboard.writeText(hex);
    setCopiedIndex(idx);
    setTimeout(() => setCopiedIndex(null), 1500);
  };

  if (!colors.length && !loading) return null;

  return (
    <div className="mt-3 p-3 rounded-lg bg-[var(--bg-input)] border border-[var(--border)] animate-fade-in">
      <div className="flex items-center justify-between mb-2">
        <span className="text-xs font-semibold text-[var(--text-soft)] flex items-center gap-1.5">
          <Palette size={14} className="text-[var(--accent)]" /> {t.paletteExtracted}
        </span>
        {loading && <span className="text-xs text-[var(--text-dim)] animate-pulse">{t.analyzingTones}</span>}
      </div>

      <div className="flex items-center gap-2 flex-wrap">
        {colors.map((hex, idx) => (
          <button
            key={idx}
            onClick={() => copyToClipboard(hex, idx)}
            title={`${t.copyHex} ${hex}`}
            className="group relative flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-mono border border-black/10 transition-all hover:scale-105"
            style={{ backgroundColor: hex, color: getContrastTextColor(hex) }}
          >
            <span>{hex}</span>
            {copiedIndex === idx ? <Check size={12} /> : <Copy size={12} className="opacity-0 group-hover:opacity-100 transition-opacity" />}
          </button>
        ))}
      </div>
    </div>
  );
}

function getContrastTextColor(hexColor) {
  const r = parseInt(hexColor.substr(1, 2), 16);
  const g = parseInt(hexColor.substr(3, 2), 16);
  const b = parseInt(hexColor.substr(5, 2), 16);
  const yiq = ((r * 299) + (g * 587) + (b * 114)) / 1000;
  return yiq >= 128 ? '#1a1a1a' : '#ffffff';
}
