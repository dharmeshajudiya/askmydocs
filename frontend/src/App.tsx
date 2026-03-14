import { useEffect, useState } from "react";
import type { Document } from "./api/documents";
import ChatInterface from "./components/ChatInterface";
import DocumentList from "./components/DocumentList";
import DocumentUpload from "./components/DocumentUpload";
import { useAuth } from "./hooks/useAuth";
import { useDocuments } from "./hooks/useDocuments";

// ─── Auth Screen ──────────────────────────────────────────────────────────────

function AuthScreen() {
  const { login, register, loading, error } = useAuth();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (mode === "login") {
      await login(email, password);
    } else {
      await register(email, password);
    }
  }

  return (
    <div className="min-h-screen bg-slate-900 flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-white">AskMyDocs</h1>
          <p className="text-slate-400 mt-2 text-sm">Upload documents, ask questions, get answers.</p>
        </div>

        <div className="bg-slate-800 rounded-2xl p-6 border border-slate-700">
          <div className="flex gap-2 mb-6">
            {(["login", "register"] as const).map((m) => (
              <button
                key={m}
                onClick={() => setMode(m)}
                className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
                  mode === m
                    ? "bg-blue-600 text-white"
                    : "bg-slate-700 text-slate-400 hover:text-slate-200"
                }`}
              >
                {m === "login" ? "Sign In" : "Register"}
              </button>
            ))}
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Email</label>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                placeholder="you@example.com"
                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-blue-500"
              />
            </div>
            <div>
              <label className="block text-xs text-slate-400 mb-1.5">Password</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                placeholder="••••••••"
                className="w-full bg-slate-700 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-blue-500"
              />
            </div>

            {error && (
              <p className="text-xs text-red-400 bg-red-900/30 rounded-lg px-3 py-2">{error}</p>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full py-2.5 bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white rounded-lg text-sm font-medium transition-colors"
            >
              {loading ? "Please wait…" : mode === "login" ? "Sign In" : "Create Account"}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}

// ─── Main App ─────────────────────────────────────────────────────────────────

export default function App() {
  const { isAuthenticated, logout } = useAuth();
  const { documents, loading, fetchDocuments, uploadDocument, deleteDocument, pollStatus } =
    useDocuments();
  const [selectedDoc, setSelectedDoc] = useState<Document | null>(null);

  useEffect(() => {
    if (isAuthenticated) {
      fetchDocuments();
    }
  }, [isAuthenticated, fetchDocuments]);

  if (!isAuthenticated) {
    return <AuthScreen />;
  }

  async function handleUploadStart(file: File): Promise<string> {
    const doc = await uploadDocument(file);
    return doc.id;
  }

  function handlePollStatus(
    id: string,
    onReady: () => void,
    onError: (err: string | null) => void
  ) {
    pollStatus(id, (_doc) => onReady(), onError);
  }

  return (
    <div className="min-h-screen bg-slate-900 flex flex-col">
      {/* Header */}
      <header className="h-14 bg-slate-800 border-b border-slate-700 flex items-center justify-between px-6 flex-shrink-0">
        <h1 className="text-lg font-bold text-white tracking-tight">
          Ask<span className="text-blue-400">My</span>Docs
        </h1>
        <button
          onClick={logout}
          className="text-sm text-slate-400 hover:text-white transition-colors"
        >
          Sign out
        </button>
      </header>

      {/* Body */}
      <div className="flex-1 flex overflow-hidden">
        {/* Sidebar */}
        <aside className="w-80 bg-slate-800 border-r border-slate-700 flex flex-col overflow-y-auto flex-shrink-0">
          <DocumentUpload
            onUploadComplete={() => fetchDocuments()}
            onUploadStart={handleUploadStart}
            onPollStatus={handlePollStatus}
          />
          <div className="border-t border-slate-700" />
          <DocumentList
            documents={documents}
            selectedId={selectedDoc?.id ?? null}
            onSelect={setSelectedDoc}
            onDelete={deleteDocument}
            loading={loading}
          />
        </aside>

        {/* Chat */}
        <main className="flex-1 flex overflow-hidden">
          <ChatInterface selectedDocument={selectedDoc} />
        </main>
      </div>
    </div>
  );
}
