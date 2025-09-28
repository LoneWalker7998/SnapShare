'use client';

import { useState } from 'react';
import FileUpload from '@/components/FileUpload';
import FileDownload from '@/components/FileDownload';
import InviteCode from '@/components/InviteCode';
import axios from 'axios';
import { FiUpload, FiDownload } from 'react-icons/fi';

export default function Home() {
  const [tab, setTab] = useState<'upload' | 'download'>('upload');

  // Upload state
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);

  // Lifted selected files so they persist across tab switches
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);

  // Invite / share state returned from backend
  const [port, setPort] = useState<number | null>(null);
  const [servedName, setServedName] = useState<string | null>(null);
  const [isZip, setIsZip] = useState<boolean | null>(null);

  // Download state
  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  // Upload handler
  const handleFilesUpload = async (files: File[]) => {
    setSelectedFiles(files);

    setIsUploading(true);
    setUploadError(null);
    setUploadProgress(0);
    setPort(null);
    setServedName(null);
    setIsZip(null);

    try {
      const formData = new FormData();
      files.forEach((f) => formData.append('files', f, f.name));
      formData.append(
        'meta',
        new Blob([JSON.stringify({ filenames: files.map((f) => f.name) })], {
          type: 'application/json',
        }),
      );

      const response = await axios.post('/api/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (ev) => {
          if (!ev.total) return;
          const percent = Math.round((ev.loaded * 100) / ev.total);
          setUploadProgress(percent);
        },
        timeout: 0,
      });

      const data = response.data;
      setPort(data.inviteCode || data.port || null);
      setServedName(data.servedName || data.zipName || null);
      setIsZip(!!data.isZip);

      // ✅ Do not clear files here, let user remove them manually
      setTab('upload');
    } catch (err: any) {
      console.error('Upload error:', err);
      const msg =
        err?.response?.data?.error ||
        err?.response?.data ||
        err?.message ||
        'Upload failed';
      setUploadError(String(msg));
      alert(msg);
    } finally {
      setIsUploading(false);
      setUploadProgress(null);
    }
  };

  // Download handler
  const handleDownload = async (portToUse: number) => {
    setIsDownloading(true);
    setDownloadError(null);
    try {
      const response = await axios.get(`/api/download/${portToUse}`, {
        responseType: 'blob',
      });

      const url = window.URL.createObjectURL(new Blob([response.data]));
      const a = document.createElement('a');
      let filename = servedName || 'download';
      const contentDisposition = response.headers['content-disposition'];
      if (contentDisposition) {
        const match = contentDisposition.match(/filename="?([^"]+)"?/);
        if (match) filename = match[1];
      } else if (isZip && filename && !filename.endsWith('.zip')) {
        filename = `${filename}.zip`;
      }
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err: any) {
      console.error('Download failed:', err);
      setDownloadError(err?.response?.data?.error || err?.message || 'Download failed');
      alert(
        'Download failed: ' +
          (err?.response?.data?.error || err?.message || 'Unknown'),
      );
    } finally {
      setIsDownloading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 py-12 px-4">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <header className="mb-6 text-center">
          <h1 className="text-4xl font-extrabold">SnapShare</h1>
          <p className="text-sm text-gray-500">Fast & simple file sharing</p>
        </header>

        {/* Tabs */}
        <div className="mb-6 flex items-center justify-center">
          <button
            onClick={() => setTab('upload')}
            className={`flex items-center gap-2 px-4 py-2 rounded ${
              tab === 'upload'
                ? 'bg-blue-600 text-white'
                : 'text-gray-600 bg-white border'
            }`}
          >
            <FiUpload /> Upload
          </button>
          <button
            onClick={() => setTab('download')}
            className={`ml-3 flex items-center gap-2 px-4 py-2 rounded ${
              tab === 'download'
                ? 'bg-blue-600 text-white'
                : 'text-gray-600 bg-white border'
            }`}
          >
            <FiDownload /> Download
          </button>
        </div>

        {/* Main content */}
        {tab === 'upload' && (
          <div
            className={
              port
                ? 'grid grid-cols-1 lg:grid-cols-3 gap-6'
                : 'max-w-3xl mx-auto'
            }
          >
            <div className={port ? 'lg:col-span-2' : ''}>
              <FileUpload
                onFilesUpload={handleFilesUpload}
                isUploading={isUploading}
                progress={uploadProgress}
                error={uploadError}
                files={selectedFiles}
                setFiles={setSelectedFiles}
              />

              {servedName && (
                <div className="mt-4 text-sm text-gray-700">
                  Recipient will download:{' '}
                  <strong>{servedName}</strong> {isZip ? '(zip)' : ''}
                </div>
              )}
            </div>

            {port && (
              <aside className="lg:col-span-1">
                <div className="sticky top-24">
                  <InviteCode
                    port={port}
                    servedName={servedName}
                    isZip={isZip}
                  />
                </div>
              </aside>
            )}
          </div>
        )}

        {tab === 'download' && (
          <div className="max-w-3xl mx-auto">
            <FileDownload
              onDownload={handleDownload}
              isDownloading={isDownloading}
              error={downloadError}
            />
            <div className="mt-4 text-sm text-gray-500">
              Enter the invite code (port) to download a file. If multiple files
              were uploaded you'll receive a zip.
            </div>
          </div>
        )}

        <footer className="mt-12 text-center text-gray-500 text-sm">
          <p>SnapShare &copy; {new Date().getFullYear()}</p>
        </footer>
      </div>
    </div>
  );
}
