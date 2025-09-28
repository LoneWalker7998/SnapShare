'use client';

import React, { useState, useCallback, useMemo } from 'react';
import { useDropzone, type Accept } from 'react-dropzone';
import {
  FiUpload,
  FiFile,
  FiTrash2,
  FiImage,
  FiFileText,
  FiArchive,
  FiVideo,
  FiMusic,
} from 'react-icons/fi';

interface FileUploadProps {
  onFilesUpload: (files: File[]) => void;
  isUploading: boolean;
  progress?: number | null;
  error?: string | null;
  accepted?: string;
  // controlled props (optional) — setFiles must accept updater form too
  files?: File[];
  setFiles?: React.Dispatch<React.SetStateAction<File[]>>;
}

const ONE_GB = 1024 * 1024 * 1024; // 1 GiB
const DEFAULT_MAX_UPLOAD_SIZE = ONE_GB; // 1 GB client-side limit

function fileIconForName(name = '') {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  if (ext.match(/^(png|jpe?g|gif|webp|bmp|svg)$/)) return <FiImage className="w-5 h-5 text-gray-600" />;
  if (ext.match(/^(zip|tar|gz|rar|7z)$/)) return <FiArchive className="w-5 h-5 text-gray-600" />;
  if (ext.match(/^(mp4|mkv|webm|mov|avi)$/)) return <FiVideo className="w-5 h-5 text-gray-600" />;
  if (ext.match(/^(mp3|wav|flac|aac)$/)) return <FiMusic className="w-5 h-5 text-gray-600" />;
  if (ext.match(/^(txt|md|csv)$/)) return <FiFileText className="w-5 h-5 text-gray-600" />;
  return <FiFile className="w-5 h-5 text-gray-600" />;
}

export default function FileUpload({
  onFilesUpload,
  isUploading,
  progress = null,
  error = null,
  accepted,
  files: filesProp,
  setFiles: setFilesProp,
}: FileUploadProps) {
  // internal fallback state when component is uncontrolled
  const [internalFiles, setInternalFiles] = useState<File[]>([]);
  const files = filesProp ?? internalFiles;
  const setFiles = setFilesProp ?? setInternalFiles;

  const [maxSize] = useState<number>(DEFAULT_MAX_UPLOAD_SIZE);

  const defaultAccept: Accept = useMemo(() => ({
    'image/*': [],
    'application/pdf': [],
    'application/zip': ['.zip'],
    'application/x-zip-compressed': ['.zip'],
    'application/octet-stream': ['.zip'],
    'video/*': [],
    'audio/*': [],
    'text/*': [],
    'application/msword': ['.doc'],
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'],
  }), []);

  const acceptProp = accepted ? (accepted as any) : defaultAccept;

  const onDrop = useCallback((acceptedFiles: File[]) => {
    // use updater form so setFiles works for both controlled and uncontrolled usages
    setFiles((prev) => {
      const merged = [...prev];
      for (const f of acceptedFiles) {
        if (!merged.some(x => x.name === f.name && x.size === f.size)) merged.push(f);
      }
      return merged;
    });
  }, [setFiles]);

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    multiple: true,
    maxSize,
    accept: acceptProp,
  });

  const removeFile = (index: number) => {
    setFiles((prev) => prev.filter((_, i) => i !== index));
  };

  const totalBytes = useMemo(() => files.reduce((s, f) => s + f.size, 0), [files]);
  const totalMB = Math.round(totalBytes / (1024 * 1024));
  const maxMB = Math.round(maxSize / (1024 * 1024));

  const handleUploadClick = async () => {
    if (files.length === 0) {
      alert('Please select at least one file to upload.');
      return;
    }
    if (totalBytes > maxSize) {
      alert(`Total selected files (${totalMB} MB) exceed the allowed upload size of ${maxMB} MB.`);
      return;
    }
    onFilesUpload(files);
  };

  return (
    <div className="p-4 bg-white rounded shadow-sm">
      <div {...getRootProps()} className="cursor-pointer border border-dashed border-gray-300 p-6 rounded text-center">
        <input {...getInputProps()} />
        <div className="flex items-center justify-center space-x-3">
          <FiUpload className="w-6 h-6" />
          <div>
            <strong>{isDragActive ? 'Drop files here' : 'Drag & drop files here'}</strong>
            <div className="text-xs text-gray-500">or click to select files (multiple allowed)</div>
            <div className="text-xs text-gray-400 mt-1">Supported: images, PDFs, docs, zip, video, audio, text.</div>
          </div>
        </div>
      </div>

      {files.length > 0 && (
        <div className="mt-4">
          <div className="text-sm font-medium mb-2">Files to send ({files.length})</div>
          <ul className="space-y-2 max-h-60 overflow-auto">
            {files.map((f, i) => (
              <li key={`${f.name}-${f.size}-${i}`} className="flex items-center justify-between border rounded p-2">
                <div className="flex items-center space-x-3">
                  {fileIconForName(f.name)}
                  <div>
                    <div className="text-sm font-medium">{f.name}</div>
                    <div className="text-xs text-gray-500">{Math.round(f.size / 1024)} KB</div>
                  </div>
                </div>
                <button type="button" onClick={() => removeFile(i)} className="text-red-500 hover:text-red-700">
                  <FiTrash2 />
                </button>
              </li>
            ))}
          </ul>

          <div className="mt-4 flex items-center justify-between">
            <div className="text-xs text-gray-500">
              Total: <strong>{totalMB} MB</strong> • Max: {maxMB} MB
            </div>
            <button
              onClick={handleUploadClick}
              disabled={isUploading}
              className="px-4 py-2 bg-blue-600 text-white rounded disabled:opacity-60"
            >
              {isUploading ? 'Uploading...' : 'Upload all'}
            </button>
          </div>

          {progress !== null && (
            <div className="mt-3">
              <div className="w-full bg-gray-200 rounded h-2">
                <div style={{ width: `${progress}%` }} className="h-2 bg-blue-500 rounded" />
              </div>
            </div>
          )}

          {error && <div className="mt-2 text-sm text-red-600">{error}</div>}
        </div>
      )}

      <div className="mt-4 text-sm text-gray-600">
        <div className="font-medium mb-1">Supported file types</div>
        <div className="flex flex-wrap gap-3 items-center">
          <div className="flex items-center space-x-2"><FiImage/> <span className="text-xs">Images</span></div>
          <div className="flex items-center space-x-2"><FiFileText/> <span className="text-xs">Text / Docs</span></div>
          <div className="flex items-center space-x-2"><FiArchive/> <span className="text-xs">Archives</span></div>
          <div className="flex items-center space-x-2"><FiVideo/> <span className="text-xs">Video</span></div>
          <div className="flex items-center space-x-2"><FiMusic/> <span className="text-xs">Audio</span></div>
          <div className="flex items-center space-x-2"><FiFile/> <span className="text-xs">PDF / Others</span></div>
        </div>
      </div>
    </div>
  );
}
