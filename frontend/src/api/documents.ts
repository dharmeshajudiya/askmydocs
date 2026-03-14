import client from "./client";

export interface Document {
  id: string;
  filename: string;
  status: string;
  total_chunks: number | null;
  created_at: string;
  updated_at: string;
}

export interface DocumentStatus {
  id: string;
  status: string;
  total_chunks: number | null;
  error_log: string | null;
}

export async function uploadDocument(file: File): Promise<Document> {
  const formData = new FormData();
  formData.append("file", file);
  const { data } = await client.post<Document>("/documents", formData);
  return data;
}

export async function listDocuments(): Promise<Document[]> {
  const { data } = await client.get<Document[]>("/documents");
  return data;
}

export async function getDocumentStatus(id: string): Promise<DocumentStatus> {
  const { data } = await client.get<DocumentStatus>(`/documents/${id}/status`);
  return data;
}

export async function deleteDocument(id: string): Promise<void> {
  await client.delete(`/documents/${id}`);
}
