import type { Document } from "../api/documents";

interface Props {
  documents: Document[];
  selectedId: string | null;
  onSelect: (doc: Document) => void;
  onDelete: (id: string) => Promise<void>;
  loading: boolean;
}

const STATUS_STYLES: Record<string, string> = {
  queued: "bg-slate-700 text-slate-300",
  processing: "bg-yellow-900/60 text-yellow-300 animate-pulse",
  ready: "bg-green-900/60 text-green-300",
  error: "bg-red-900/60 text-red-300",
};

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function fileIcon(filename: string): string {
  const ext = filename.split(".").pop()?.toLowerCase();
  if (ext === "pdf") return "📕";
  if (ext === "docx") return "📘";
  return "📄";
}

export default function DocumentList({ documents, selectedId, onSelect, onDelete, loading }: Props) {
  if (loading) {
    return (
      <div className="p-4 text-center text-slate-500 text-sm">Loading documents…</div>
    );
  }

  if (documents.length === 0) {
    return (
      <div className="p-4 text-center text-slate-500 text-sm">
        No documents yet. Upload one to get started.
      </div>
    );
  }

  async function handleDelete(e: React.MouseEvent, id: string) {
    e.stopPropagation();
    if (!window.confirm("Delete this document and all its data?")) return;
    await onDelete(id);
  }

  return (
    <div className="p-4 space-y-2">
      <h2 className="text-sm font-semibold text-slate-400 uppercase tracking-wider mb-3">
        Documents
      </h2>
      {documents.map((doc) => (
        <div
          key={doc.id}
          onClick={() => doc.status === "ready" && onSelect(doc)}
          className={`rounded-lg border p-3 cursor-pointer transition-all ${
            selectedId === doc.id
              ? "border-blue-500 bg-blue-500/10"
              : "border-slate-700 bg-slate-800 hover:border-slate-500"
          } ${doc.status !== "ready" ? "opacity-60 cursor-default" : ""}`}
        >
          <div className="flex items-start justify-between gap-2">
            <div className="flex items-center gap-2 min-w-0">
              <span className="text-lg flex-shrink-0">{fileIcon(doc.filename)}</span>
              <div className="min-w-0">
                <p className="text-sm text-slate-200 truncate font-medium">{doc.filename}</p>
                <p className="text-xs text-slate-500 mt-0.5">{formatDate(doc.created_at)}</p>
              </div>
            </div>
            <button
              onClick={(e) => handleDelete(e, doc.id)}
              className="text-slate-600 hover:text-red-400 transition-colors flex-shrink-0 text-xs px-1"
              title="Delete"
            >
              ✕
            </button>
          </div>

          <div className="flex items-center justify-between mt-2">
            <span
              className={`text-xs px-2 py-0.5 rounded-full font-medium ${STATUS_STYLES[doc.status] ?? "bg-slate-700 text-slate-300"}`}
            >
              {doc.status}
            </span>
            {doc.status === "ready" && doc.total_chunks != null && (
              <span className="text-xs text-slate-500">{doc.total_chunks} chunks</span>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
