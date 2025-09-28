'use client';

import React, { useEffect, useState } from 'react';
import { FiCopy, FiCheck, FiMail, FiSend, FiImage, FiFileText, FiArchive, FiVideo, FiMusic, FiFile } from 'react-icons/fi';

interface InviteCodeProps {
  port: number | null;
  // servedName/isZip are accepted but not displayed here (kept for compatibility)
  servedName?: string | null;
  isZip?: boolean | null;
}

type Toast = { id: number; type: 'success' | 'error' | 'info'; message: string };

export default function InviteCode({ port }: InviteCodeProps) {
  const [copied, setCopied] = useState(false);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [showEmailModal, setShowEmailModal] = useState(false);
  const [emailTarget, setEmailTarget] = useState('');
  const [isSending, setIsSending] = useState(false);

  useEffect(() => {
    if (copied) {
      const t = setTimeout(() => setCopied(false), 2000);
      return () => clearTimeout(t);
    }
  }, [copied]);

  if (!port) return null;

  const pushToast = (type: Toast['type'], message: string) => {
    const id = Date.now() + Math.floor(Math.random() * 1000);
    setToasts((s) => [...s, { id, type, message }]);
    setTimeout(() => {
      setToasts((s) => s.filter((t) => t.id !== id));
    }, 3600);
  };

  const copyCode = async () => {
    try {
      await navigator.clipboard.writeText(String(port));
      setCopied(true);
      pushToast('success', 'Invite code copied to clipboard');
    } catch (e) {
      pushToast('error', 'Copy failed — please copy manually: ' + String(port));
    }
  };

  const openEmailModal = () => {
    setEmailTarget('');
    setShowEmailModal(true);
  };

  const closeEmailModal = () => {
    setShowEmailModal(false);
    setIsSending(false);
  };

  const shareViaEmail = () => {
    if (!emailTarget || !emailTarget.includes('@')) {
      pushToast('error', 'Enter a valid email address');
      return;
    }
    setIsSending(true);
    try {
      const subject = encodeURIComponent('SnapShare: file shared with you');
      const body = encodeURIComponent(
        `Hi,\n\nA file was shared with you on SnapShare.\nPlease use invite code: ${port}\n\nOpen SnapShare and enter the code to download.\n\n(Valid while the sender's session is active.)`
      );
      const mailto = `mailto:${encodeURIComponent(emailTarget)}?subject=${subject}&body=${body}`;
      window.open(mailto, '_blank');
      pushToast('success', 'Email client opened (use Send in your mail app).');
      closeEmailModal();
    } catch (e: any) {
      pushToast('error', 'Failed to open email client: ' + (e?.message || 'unknown'));
      setIsSending(false);
    }
  };

  const shareViaWhatsApp = () => {
    try {
      const text = `I shared a file via SnapShare — use code ${port} to download.`;
      const waUrl = `https://wa.me/?text=${encodeURIComponent(text)}`;
      window.open(waUrl, '_blank');
      pushToast('info', 'WhatsApp opened');
    } catch (e: any) {
      pushToast('error', 'Failed to open WhatsApp: ' + (e?.message || 'unknown'));
    }
  };

  const shareViaSMS = () => {
    try {
      const body = `SnapShare file — use code ${port}`;
      const smsUrl = `sms:?&body=${encodeURIComponent(body)}`;
      window.open(smsUrl, '_blank');
      pushToast('info', 'SMS compose opened');
    } catch (e: any) {
      pushToast('error', 'Failed to open SMS app: ' + (e?.message || 'unknown'));
    }
  };

  return (
    <>
      <div className="p-4 bg-white border rounded-lg shadow text-center">
        <h3 className="text-lg font-semibold text-gray-800 mb-1">File Ready to Share!</h3>
        <p className="text-sm text-gray-600 mb-4">Share the code below with the recipient</p>

        <div className="flex items-stretch justify-center mb-4">
          <div className="bg-gray-100 px-5 py-3 rounded-l-md border border-r-0 border-gray-300 font-mono text-xl tracking-wider">
            {port}
          </div>
          <button
            onClick={copyCode}
            className="px-4 bg-blue-600 text-white rounded-r-md flex items-center gap-2"
            title="Copy code"
          >
            {copied ? <FiCheck className="w-5 h-5" /> : <FiCopy className="w-5 h-5" />} <span className="text-sm">Copy</span>
          </button>
        </div>

        <div className="flex items-center justify-center gap-3 mb-4">
          <button onClick={openEmailModal} className="px-3 py-2 bg-white border rounded flex items-center gap-2">
            <FiMail /> Email
          </button>

          <button onClick={shareViaWhatsApp} className="px-3 py-2 bg-green-500 text-white rounded flex items-center gap-2">
            <FiSend /> WhatsApp
          </button>

          <button onClick={shareViaSMS} className="px-3 py-2 bg-blue-600 text-white rounded flex items-center gap-2">
            <FiSend /> SMS
          </button>
        </div>

        {/* Supported file types row */}
        <div className="mt-3 border-t pt-3">
          <div className="text-sm text-gray-700 mb-2 font-semibold">Supported file types</div>
          <div className="flex flex-wrap items-center justify-center gap-4">
            <div className="flex flex-col items-center text-xs text-gray-600">
              <FiImage className="w-6 h-6" />
              <div>Images</div>
            </div>
            <div className="flex flex-col items-center text-xs text-gray-600">
              <FiFileText className="w-6 h-6" />
              <div>Docs</div>
            </div>
            <div className="flex flex-col items-center text-xs text-gray-600">
              <FiArchive className="w-6 h-6" />
              <div>Archives</div>
            </div>
            <div className="flex flex-col items-center text-xs text-gray-600">
              <FiVideo className="w-6 h-6" />
              <div>Video</div>
            </div>
            <div className="flex flex-col items-center text-xs text-gray-600">
              <FiMusic className="w-6 h-6" />
              <div>Audio</div>
            </div>
            <div className="flex flex-col items-center text-xs text-gray-600">
              <FiFile className="w-6 h-6" />
              <div>PDF / Other</div>
            </div>
          </div>
        </div>

        <p className="mt-4 text-xs text-gray-500">Code is valid while the sender's sharing session is active.</p>
      </div>

      {/* Email modal */}
      {showEmailModal && (
        <div className="fixed inset-0 flex items-center justify-center z-50">
          <div className="absolute inset-0 bg-black opacity-40" onClick={closeEmailModal} />
          <div className="bg-white rounded shadow-lg p-6 z-50 w-96">
            <h4 className="font-semibold mb-2">Share via Email (opens your mail client)</h4>
            <input
              type="email"
              value={emailTarget}
              onChange={(e) => setEmailTarget(e.target.value)}
              placeholder="Recipient email"
              className="w-full border px-2 py-1 mb-3 rounded"
            />
            <div className="flex justify-end gap-2">
              <button className="px-3 py-1 border rounded" onClick={closeEmailModal}>
                Cancel
              </button>
              <button
                className="px-3 py-1 bg-blue-600 text-white rounded disabled:opacity-60"
                onClick={shareViaEmail}
                disabled={isSending}
              >
                {isSending ? 'Preparing…' : 'Open Mail'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Toasts */}
      <div aria-live="polite" className="fixed bottom-6 right-6 z-50 flex flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={`p-3 rounded shadow-lg text-white ${t.type === 'success' ? 'bg-green-600' : t.type === 'error' ? 'bg-red-600' : 'bg-gray-800'}`}
          >
            {t.message}
          </div>
        ))}
      </div>
    </>
  );
}
