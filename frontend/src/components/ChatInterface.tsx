import { useEffect, useRef, useState } from "react";
import type { Document } from "../api/documents";
import type { SourceChunk } from "../api/query";
import { streamQuery } from "../api/query";

interface Message {
  role: "user" | "assistant";
  content: string;
  chunks?: SourceChunk[];
}

interface Props {
  selectedDocument: Document | null;
}

function SourceCard({ chunks }: { chunks: SourceChunk[] }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="mt-2">
      <button
        onClick={() => setOpen((v) => !v)}
        className="text-xs text-blue-400 hover:text-blue-300 flex items-center gap-1"
      >
        <span>{open ? "▾" : "▸"}</span> {chunks.length} source{chunks.length !== 1 ? "s" : ""}
      </button>
      {open && (
        <div className="mt-2 space-y-2">
          {chunks.map((c, i) => (
            <div key={i} className="rounded border border-slate-600 bg-slate-800 p-2 text-xs">
              <div className="flex justify-between text-slate-400 mb-1">
                <span>Page {c.page}</span>
                <span>Score: {c.score.toFixed(3)}</span>
              </div>
              <p className="text-slate-300 line-clamp-3">{c.chunk_text}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TypingIndicator() {
  return (
    <div className="flex items-center gap-1 px-3 py-2">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="w-1.5 h-1.5 bg-slate-400 rounded-full animate-bounce"
          style={{ animationDelay: `${i * 0.15}s` }}
        />
      ))}
    </div>
  );
}

export default function ChatInterface({ selectedDocument }: Props) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streaming]);

  // Reset when document changes
  useEffect(() => {
    setMessages([]);
    setInput("");
    setStreaming(false);
  }, [selectedDocument?.id]);

  function handleSend() {
    if (!input.trim() || !selectedDocument || streaming) return;

    const question = input.trim();
    setInput("");
    setMessages((prev) => [...prev, { role: "user", content: question }]);
    setStreaming(true);

    let assistantContent = "";
    const assistantIndex = messages.length + 1;

    setMessages((prev) => [...prev, { role: "assistant", content: "" }]);

    const abort = streamQuery(
      selectedDocument.id,
      question,
      (token) => {
        assistantContent += token;
        setMessages((prev) =>
          prev.map((m, i) =>
            i === assistantIndex ? { ...m, content: assistantContent } : m
          )
        );
      },
      () => {
        setStreaming(false);
        abortRef.current = null;
      },
      () => {
        setMessages((prev) =>
          prev.map((m, i) =>
            i === assistantIndex
              ? { ...m, content: assistantContent || "An error occurred." }
              : m
          )
        );
        setStreaming(false);
      }
    );

    abortRef.current = abort;
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  if (!selectedDocument) {
    return (
      <div className="flex-1 flex items-center justify-center text-slate-500">
        <div className="text-center">
          <div className="text-4xl mb-3">💬</div>
          <p className="text-lg font-medium">Select a document to start asking questions</p>
          <p className="text-sm mt-1">Upload a PDF, DOCX, or TXT file from the sidebar</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col h-full">
      {/* Document header */}
      <div className="px-6 py-3 border-b border-slate-700 bg-slate-800/50">
        <p className="text-sm text-slate-400">
          Asking about:{" "}
          <span className="text-slate-200 font-medium">{selectedDocument.filename}</span>
        </p>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
        {messages.length === 0 && (
          <div className="text-center text-slate-500 text-sm mt-8">
            Ask a question about <strong className="text-slate-400">{selectedDocument.filename}</strong>
          </div>
        )}

        {messages.map((msg, i) => (
          <div key={i} className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}>
            <div
              className={`max-w-[75%] rounded-2xl px-4 py-2 text-sm leading-relaxed ${
                msg.role === "user"
                  ? "bg-blue-600 text-white rounded-tr-sm"
                  : "bg-slate-700 text-slate-100 rounded-tl-sm"
              }`}
            >
              <p className="whitespace-pre-wrap">{msg.content}</p>
              {msg.role === "assistant" && msg.chunks && msg.chunks.length > 0 && (
                <SourceCard chunks={msg.chunks} />
              )}
            </div>
          </div>
        ))}

        {streaming && messages[messages.length - 1]?.content === "" && <TypingIndicator />}

        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div className="px-6 py-4 border-t border-slate-700">
        <div className="flex gap-3 items-end">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Ask a question about this document…"
            rows={2}
            disabled={streaming}
            className="flex-1 resize-none rounded-xl bg-slate-700 border border-slate-600 px-4 py-2.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-blue-500 disabled:opacity-50"
          />
          <button
            onClick={handleSend}
            disabled={!input.trim() || streaming}
            className="px-4 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm font-medium transition-colors"
          >
            {streaming ? "…" : "Send"}
          </button>
        </div>
        <p className="text-xs text-slate-600 mt-1.5">Enter to send · Shift+Enter for newline</p>
      </div>
    </div>
  );
}
