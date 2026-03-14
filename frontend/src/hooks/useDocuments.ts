import { useCallback, useState } from "react";
import * as docsApi from "../api/documents";
import type { Document } from "../api/documents";

export function useDocuments() {
  const [documents, setDocuments] = useState<Document[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchDocuments = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const docs = await docsApi.listDocuments();
      setDocuments(docs);
    } catch {
      setError("Failed to load documents.");
    } finally {
      setLoading(false);
    }
  }, []);

  async function uploadDocument(file: File): Promise<Document> {
    const doc = await docsApi.uploadDocument(file);
    setDocuments((prev) => [doc, ...prev]);
    return doc;
  }

  async function deleteDocument(id: string) {
    await docsApi.deleteDocument(id);
    setDocuments((prev) => prev.filter((d) => d.id !== id));
  }

  function pollStatus(
    id: string,
    onReady: (doc: Document) => void,
    onError: (errLog: string | null) => void
  ) {
    let pollCount = 0;
    const BASE_INTERVAL = 2000;
    const MAX_INTERVAL = 30000;
    let timeoutId: ReturnType<typeof setTimeout>;

    async function check() {
      try {
        const status = await docsApi.getDocumentStatus(id);

        if (status.status === "ready") {
          setDocuments((prev) =>
            prev.map((d) =>
              d.id === id ? { ...d, status: "ready", total_chunks: status.total_chunks } : d
            )
          );
          onReady({ ...documents.find((d) => d.id === id)!, status: "ready" });
          return;
        }

        if (status.status === "error") {
          setDocuments((prev) =>
            prev.map((d) => (d.id === id ? { ...d, status: "error" } : d))
          );
          onError(status.error_log);
          return;
        }

        pollCount++;
        const interval =
          pollCount > 10
            ? Math.min(BASE_INTERVAL * Math.pow(2, pollCount - 10), MAX_INTERVAL)
            : BASE_INTERVAL;

        timeoutId = setTimeout(check, interval);
      } catch {
        // Network error — keep polling
        timeoutId = setTimeout(check, BASE_INTERVAL);
      }
    }

    timeoutId = setTimeout(check, BASE_INTERVAL);
    return () => clearTimeout(timeoutId);
  }

  return { documents, loading, error, fetchDocuments, uploadDocument, deleteDocument, pollStatus };
}
