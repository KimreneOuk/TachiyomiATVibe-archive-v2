import React, { useState } from 'react';
import { ShieldCheck, Zap, AlertTriangle, Upload, FileImage, CheckCircle2, XCircle, RefreshCw, BarChart2, Activity } from 'lucide-react';
import { TRANSLATIONS } from '../i18n';

export default function WatermarkView({ onToast, lang = 'en' }) {
  const [verifyFile, setVerifyFile] = useState(null);
  const [verifyPreview, setVerifyPreview] = useState(null);
  const [verifyResult, setVerifyResult] = useState(null);
  const [stressResult, setStressResult] = useState('');
  const [loading, setLoading] = useState(false);
  const [isDragging, setIsDragging] = useState(false);

  const t = TRANSLATIONS[lang] || TRANSLATIONS.en;

  const handleFileSelect = (file) => {
    if (!file) {
      setVerifyFile(null);
      setVerifyPreview(null);
      setVerifyResult(null);
      setStressResult('');
      return;
    }
    if (!file.type || !file.type.startsWith('image/')) {
      onToast('Please select a valid image file (PNG, JPG, WebP)', 'warning');
      return;
    }
    setVerifyFile(file);
    setVerifyResult(null);
    setStressResult('');
    setVerifyPreview(URL.createObjectURL(file));
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

  const handleVerify = async () => {
    if (!verifyFile) {
      onToast('Select or drag an image first', 'warning');
      return;
    }

    setLoading(true);
    setVerifyResult(null);
    setStressResult('');
    try {
      const formData = new FormData();
      formData.append('file', verifyFile);

      const resp = await fetch('/api/watermark/verify', {
        method: 'POST',
        body: formData,
      });

      if (resp.ok) {
        const data = await resp.json();
        setVerifyResult(data);
        const isDetected = Boolean(data.detected ?? data.found);
        if (isDetected) {
          onToast(t.authenticDetected, 'success');
        } else {
          onToast(t.authenticNotFound, 'info');
        }
      }
    } catch (err) {
      onToast('Error verifying watermark', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleStressTest = async () => {
    if (!verifyFile) {
      onToast('Select or drag an image first', 'warning');
      return;
    }

    setLoading(true);
    try {
      const formData = new FormData();
      formData.append('file', verifyFile);

      const resp = await fetch('/api/watermark/stress', {
        method: 'POST',
        body: formData,
      });

      if (resp.ok) {
        const data = await resp.json();
        setStressResult(data.markdown || '');
        onToast('Stress test completed', 'success');
      }
    } catch (err) {
      onToast('Error in stress test', 'error');
    } finally {
      setLoading(false);
    }
  };

  const isDetected = verifyResult ? Boolean(verifyResult.detected ?? verifyResult.found) : false;

  return (
    <div className="w-full h-full flex flex-col space-y-6 animate-fade-in p-2">
      <div className="glass-panel p-6 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <div className="p-3 rounded-2xl bg-gradient-to-tr from-blue-600 to-indigo-600 text-white shadow-md">
              <ShieldCheck size={28} />
            </div>
            <div>
              <h2 className="text-2xl font-bold text-[var(--text-main)]">{t.detectorTitle}</h2>
              <p className="text-sm text-[var(--text-dim)] font-medium">{t.detectorSub}</p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={handleVerify}
            disabled={loading || !verifyFile}
            className="px-6 py-3 rounded-xl bg-[var(--accent)] text-white font-bold text-sm hover:bg-[var(--accent-hover)] disabled:opacity-40 transition-all flex items-center gap-2 shadow-md cursor-pointer"
          >
            {loading ? <RefreshCw size={18} className="animate-spin" /> : <ShieldCheck size={18} />} {t.analyzeAuthBtn}
          </button>

          <button
            onClick={handleStressTest}
            disabled={loading || !verifyFile}
            className="px-6 py-3 rounded-xl bg-[var(--bg-card)] border border-[var(--border-lit)] text-[var(--text-soft)] font-bold text-sm hover:border-[var(--accent)] hover:text-[var(--accent)] disabled:opacity-40 transition-all flex items-center gap-2 cursor-pointer"
          >
            <Zap size={18} /> {t.stressTestBtn}
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 flex-1 min-h-[550px]">
        <div className="lg:col-span-6 flex flex-col space-y-4">
          <div
            onDragOver={handleDragOver}
            onDragLeave={handleDragLeave}
            onDrop={handleDrop}
            className={`glass-panel p-6 flex-1 flex flex-col justify-between space-y-4 relative overflow-hidden transition-all ${
              isDragging ? 'ring-4 ring-[var(--accent)] bg-[var(--accent-glow)] border-[var(--accent)]' : ''
            }`}
          >
            <div className="flex items-center justify-between border-b border-[var(--border)] pb-3">
              <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-dim)] flex items-center gap-2">
                <FileImage size={16} className="text-[var(--accent)]" /> {t.inspectionSample}
              </span>
              {verifyFile && (
                <span className="px-3 py-1 rounded-full bg-blue-500/10 text-blue-600 text-xs font-mono font-bold">
                  {verifyFile.name}
                </span>
              )}
            </div>

            {verifyPreview ? (
              <div className="flex-1 flex flex-col items-center justify-center relative min-h-[380px] bg-black/5 rounded-2xl p-4 border border-[var(--border)] group">
                <img
                  src={verifyPreview}
                  alt="Verification Preview"
                  className="max-h-[420px] w-auto object-contain rounded-xl shadow-lg"
                />
                <button
                  onClick={() => handleFileSelect(null)}
                  className="absolute top-4 right-4 px-3 py-1.5 rounded-xl bg-black/75 text-white text-xs font-bold hover:bg-rose-600 transition-colors cursor-pointer"
                >
                  {t.changeImgBtn}
                </button>
              </div>
            ) : (
              <label className={`flex-1 flex flex-col items-center justify-center border-2 border-dashed rounded-2xl p-10 bg-[var(--bg-card)] cursor-pointer transition-all min-h-[380px] space-y-4 text-center group ${
                isDragging ? 'border-[var(--accent)] bg-[var(--accent-glow)] scale-[0.99]' : 'border-[var(--border-lit)] hover:border-[var(--accent)]'
              }`}>
                <input
                  type="file"
                  accept="image/*"
                  onChange={(e) => handleFileSelect(e.target.files[0])}
                  className="hidden"
                />
                <div className="w-20 h-20 rounded-2xl bg-[var(--accent-glow)] flex items-center justify-center text-[var(--accent)] group-hover:scale-110 transition-transform">
                  <Upload size={36} />
                </div>
                <div>
                  <h4 className="text-lg font-bold text-[var(--text-main)] mb-1">{t.dragOrClickImg}</h4>
                  <p className="text-xs text-[var(--text-soft)] max-w-md">{t.dragOrClickSub}</p>
                </div>
              </label>
            )}

            {verifyFile && (
              <div className="grid grid-cols-3 gap-3 pt-2 text-xs font-mono text-[var(--text-soft)]">
                <div className="p-2.5 rounded-xl bg-[var(--bg-input)] border border-[var(--border)]">
                  <span className="text-[11px] text-[var(--text-dim)] block">{t.fileSize}</span>
                  <strong className="text-[var(--text-main)] font-semibold">{(verifyFile.size / 1024).toFixed(1)} KB</strong>
                </div>
                <div className="p-2.5 rounded-xl bg-[var(--bg-input)] border border-[var(--border)]">
                  <span className="text-[11px] text-[var(--text-dim)] block">{t.fileFormat}</span>
                  <strong className="text-[var(--text-main)] font-semibold">{verifyFile.type || 'Image'}</strong>
                </div>
                <div className="p-2.5 rounded-xl bg-[var(--bg-input)] border border-[var(--border)]">
                  <span className="text-[11px] text-[var(--text-dim)] block">{t.fileAlgo}</span>
                  <strong className="text-[var(--accent)] font-semibold">QIM-DCT 8x8</strong>
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="lg:col-span-6 flex flex-col space-y-4">
          <div className="glass-panel p-6 flex-1 flex flex-col justify-between space-y-5">
            <div>
              <div className="flex items-center justify-between border-b border-[var(--border)] pb-3 mb-4">
                <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-dim)] flex items-center gap-2">
                  <Activity size={16} className="text-[var(--accent)]" /> {t.reportTitle}
                </span>
                <span className="text-xs text-[var(--text-dim)] font-mono">{t.signatureLabel}</span>
              </div>

              {verifyResult ? (
                <div className={`p-6 rounded-2xl border-2 flex items-center gap-4 animate-fade-in ${
                  isDetected
                    ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-700 dark:text-emerald-300'
                    : 'bg-amber-500/10 border-amber-500/30 text-amber-700 dark:text-amber-300'
                }`}>
                  {isDetected ? (
                    <CheckCircle2 size={42} className="text-emerald-500 shrink-0" />
                  ) : (
                    <XCircle size={42} className="text-amber-500 shrink-0" />
                  )}
                  <div>
                    <h3 className="text-lg font-bold">
                      {isDetected ? t.authenticDetected : t.authenticNotFound}
                    </h3>
                    <p className="text-xs opacity-90 mt-1 leading-relaxed whitespace-pre-line font-medium">
                      {verifyResult.details || (isDetected ? t.authenticDesc : t.noWatermarkDesc)}
                    </p>
                  </div>
                </div>
              ) : (
                <div className="p-8 rounded-2xl border border-dashed border-[var(--border-lit)] bg-[var(--bg-card)] flex flex-col items-center justify-center text-center text-[var(--text-dim)] space-y-2">
                  <BarChart2 size={36} className="opacity-40" />
                  <p className="font-semibold text-sm text-[var(--text-main)]">{t.noAnalysisYet}</p>
                  <p className="text-xs text-[var(--text-soft)]">{t.noAnalysisSub}</p>
                </div>
              )}
            </div>

            {stressResult ? (
              <div className="space-y-3 pt-3 border-t border-[var(--border)]">
                <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-dim)]">{t.stressTitle}</span>
                <div className="p-4 rounded-xl bg-[var(--bg-input)] border border-[var(--border)] max-h-60 overflow-y-auto font-mono text-xs text-[var(--text-soft)] leading-relaxed whitespace-pre-line">
                  {stressResult}
                </div>
              </div>
            ) : (
              <div className="p-4 rounded-xl bg-[var(--bg-card)] border border-[var(--border)] text-xs text-[var(--text-soft)] space-y-2">
                <h5 className="font-bold text-[var(--text-main)] flex items-center gap-1.5">
                  <Zap size={14} className="text-[var(--accent)]" /> {t.stressInfoTitle}
                </h5>
                <p className="text-xs leading-relaxed">
                  {t.stressInfoDesc}
                </p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
