const TOKEN_KEY = 'rxlog_admin_token';

export function getAuthToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function setAuthToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY);
}

export async function apiFetch(url, options = {}) {
  const token = getAuthToken();
  const headers = new Headers(options.headers || {});

  if (token && !headers.has('authorization')) {
    headers.set('authorization', `Bearer ${token}`);
  }

  return fetch(url, { ...options, headers });
}
