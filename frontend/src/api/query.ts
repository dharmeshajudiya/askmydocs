import client from "./client";

export interface SourceChunk {
  chunk_text: string;
  page: number;
  score: number;
}

export interface QueryHistoryItem {
  id: string;
  document_id: string;
  question: string;
  answer: string;
  source_chunks: SourceChunk[];
  latency_ms: number | null;
  created_at: string;
}

export function streamQuery(
  documentId: string,
  question: string,
  onToken: (token: string) => void,
  onDone: () => void,
  onError: (err: Error) => void
): () => void {
  const controller = new AbortController();
  const token = localStorage.getItem("access_token");

  fetch("/api/query", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ document_id: documentId, question }),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";

        for (const line of lines) {
          if (line.startsWith("data: ")) {
            const data = line.slice(6);
            if (data === "[DONE]") {
              onDone();
              return;
            }
            onToken(data);
          }
        }
      }
      onDone();
    })
    .catch((err: Error) => {
      if (err.name !== "AbortError") {
        onError(err);
      }
    });

  return () => controller.abort();
}

export async function getQueryHistory(): Promise<QueryHistoryItem[]> {
  const { data } = await client.get<QueryHistoryItem[]>("/query/history");
  return data;
}
