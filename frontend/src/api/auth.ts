import client from "./client";

export interface TokenResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
}

export async function register(email: string, password: string): Promise<TokenResponse> {
  const { data } = await client.post<TokenResponse>("/auth/register", { email, password });
  return data;
}

export async function login(email: string, password: string): Promise<TokenResponse> {
  const { data } = await client.post<TokenResponse>("/auth/login", { email, password });
  return data;
}

export async function refreshToken(refresh_token: string): Promise<TokenResponse> {
  const { data } = await client.post<TokenResponse>("/auth/refresh", { refresh_token });
  return data;
}
