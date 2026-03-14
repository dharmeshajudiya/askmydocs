import { useRef, useState } from "react";

interface Props {
  onUploadComplete: (docId: string) => void;
  onUploadStart: (file: File) => Promise<string>;
  onPollStatus: (
    id: string,
    onReady: () => void,
    onError: (err: string | null) => void
  ) => void;
}

type UploadState = "idle" | "uploading" | "processing" | "ready" | "error";

export default function DocumentUpload({ onUploadComplete, onUploadStart, onPollStatus }: Props) {
  const [state, setState] = useState<UploadState>("idle");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [pendingFile, setPendingFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  async function handleFile(file: File) {
    const allowed = ["application/pdf", "text/plain",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document"];
    if (!allowed.includes(file.type)) {
      setErrorMsg("Only PDF, DOCX, and TXT files are supported.");
      return;
    }

    setPendingFile(file);
    setState("uploading");
    setErrorMsg(null);

    try {
      const docId = await onUploadStart(file);
      setState("processing");
      onPollStatus(
        docId,
        () => {
          setState("ready");
          onUploadComplete(docId);
          setTimeout(() => setState("idle"), 2000);
        },
        (err) => {
          setState("error");
          setErrorMsg(err ?? "Ingestion failed.");
        }
      );
    } catch {
      setState("error");
      setErrorMsg("Upload failed. Please try again.");
    }
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) handleFile(file);
  }

  function onInputChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) handleFile(file);
    e.target.value = "";
  }

  return (
    <div className="p-4">
      <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-3">
        Upload Document
      </h2>

      <div
        className={`border-2 border-dashed rounded-lg p-6 text-center cursor-pointer transition-colors ${
          dragOver
            ? "border-blue-400 bg-blue-500/10"
            : "border-slate-600 hover:border-slate-400 hover:bg-slate-700/30"
        }`}
        onClick={() => inputRef.current?.click()}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
      >
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.docx,.txt"
          className="hidden"
          onChange={onInputChange}
        />

        {state === "idle" && (
          <>
            <div className="text-3xl mb-2">📄</div>
            <p className="text-sm text-slate-300">Drop a file here or click to browse</p>
            <p className="text-xs text-slate-500 mt-1">PDF, DOCX, TXT</p>
          </>
        )}

        {state === "uploading" && (
          <div className="flex flex-col items-center gap-2">
            <div className="w-6 h-6 border-2 border-blue-400 border-t-transparent rounded-full animate-spin" />
            <p className="text-sm text-slate-300">Uploading {pendingFile?.name}…</p>
          </div>
        )}

        {state === "processing" && (
          <div className="flex flex-col items-center gap-2">
            <div className="w-6 h-6 border-2 border-yellow-400 border-t-transparent rounded-full animate-spin" />
            <p className="text-sm text-yellow-300">
              Processing<span className="animate-pulse">...</span>
            </p>
            <p className="text-xs text-slate-500">{pendingFile?.name}</p>
          </div>
        )}

        {state === "ready" && (
          <div className="flex flex-col items-center gap-2">
            <span className="text-2xl">✅</span>
            <p className="text-sm text-green-400">Ready!</p>
          </div>
        )}

        {state === "error" && (
          <div className="flex flex-col items-center gap-2">
            <span className="text-2xl">❌</span>
            <p className="text-sm text-red-400">{errorMsg}</p>
            <p
              className="text-xs text-slate-400 underline cursor-pointer"
              onClick={(e) => { e.stopPropagation(); setState("idle"); setErrorMsg(null); }}
            >
              Try again
            </p>
          </div>
        )}
      </div>

      {pendingFile && state === "idle" && (
        <p className="text-xs text-slate-500 mt-2 truncate">Last: {pendingFile.name}</p>
      )}
    </div>
  );
}
