'use client';

import { useState } from 'react';
import { FiDownload } from 'react-icons/fi';

interface FileDownloadProps {
  onDownload: (port: number) => Promise<void>;
  isDownloading: boolean;
  error?: string | null;
}

export default function FileDownload({ onDownload, isDownloading, error = null }: FileDownloadProps) {
  const [inviteCode, setInviteCode] = useState('');
  const [localError, setLocalError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLocalError('');
    const trimmed = inviteCode.trim();
    if (!/^\d+$/.test(trimmed)) {
      setLocalError('Invite code must be a numeric port number.');
      return;
    }
    const port = parseInt(trimmed, 10);
    if (port <= 0 || port > 65535) {
      setLocalError('Invalid port number (1-65535).');
      return;
    }
    try {
      await onDownload(port);
    } catch (err: any) {
      setLocalError(err?.message ?? 'Download failed');
    }
  };

  return (
    <div className="p-4 bg-white rounded shadow-sm border">
      <form onSubmit={handleSubmit}>
        <label className="block text-sm font-medium text-gray-700">Invite code / port</label>
        <input
          type="text"
          value={inviteCode}
          onChange={(e) => setInviteCode(e.target.value)}
          className="mt-1 block w-full rounded border px-2 py-1"
          placeholder="e.g. 54033"
          disabled={isDownloading}
        />
        {(localError || error) && <div className="mt-2 text-sm text-red-600">{localError || error}</div>}
        <button type="submit" className="mt-3 px-4 py-2 bg-blue-600 text-white rounded" disabled={isDownloading}>
          {isDownloading ? 'Downloading...' : (<><FiDownload className="inline mr-2" /> Download</>)}
        </button>
      </form>
    </div>
  );
}
