/** Unsigned JWT-like token with the given `exp` (seconds): enough for client-side expiry checks. */
export function fakeToken(expSeconds: number): string {
  const b64 = (o: object) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'admin', exp: expSeconds })}.sig`;
}
