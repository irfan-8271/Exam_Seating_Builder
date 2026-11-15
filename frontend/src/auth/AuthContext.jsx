import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { authApi } from '../api/endpoints'
import { setUnauthorizedHandler, tokenStore } from '../api/client'

/**
 * The session: who is signed in, and what they may do.
 *
 * Two kinds of account exist, and they share one door:
 *
 *  - an **institution account** (a teacher, an exam cell) — the normal flow:
 *    sign in, bring your roster, generate a plan;
 *  - a **platform administrator** — the operator of the service as a whole.
 *    They sign in through the same form and are taken to the administration
 *    screens, because there is nothing in a customer workspace for them to do.
 *
 * The separate administration door at /admin/login still exists for
 * operators who bookmark it, but nothing links to it.
 */

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => tokenStore.user())
  const [institution, setInstitution] = useState(() => tokenStore.institution())
  const [checking, setChecking] = useState(() => Boolean(tokenStore.get()))

  const signOutLocally = useCallback(() => {
    tokenStore.clear()
    setUser(null)
    setInstitution(null)
  }, [])

  // A token that the backend does not accept ends the session.
  useEffect(() => {
    setUnauthorizedHandler(signOutLocally)
  }, [signOutLocally])

  // On a reload the token is still there — ask the backend who it belongs to.
  useEffect(() => {
    let cancelled = false
    const token = tokenStore.get()
    if (!token) {
      setChecking(false)
      return undefined
    }
    authApi
      .me()
      .then((session) => {
        if (cancelled) return
        tokenStore.setUser(session.user)
        tokenStore.setInstitution(session.institution)
        setUser(session.user)
        setInstitution(session.institution)
      })
      .catch(() => {
        if (!cancelled) signOutLocally()
      })
      .finally(() => {
        if (!cancelled) setChecking(false)
      })
    return () => {
      cancelled = true
    }
  }, [signOutLocally])

  /** Remembers the session in the browser. */
  const remember = useCallback((session) => {
    tokenStore.set(session.token)
    tokenStore.setUser(session.user)
    tokenStore.setInstitution(session.institution)
    setUser(session.user)
    setInstitution(session.institution)
    return session
  }, [])

  /**
   * The one door.
   *
   * The backend's normal sign-in accepts every account, a platform
   * administrator included. What differs is where the person lands: the
   * caller is told the role in the session and sends them to the
   * administration screens or to their own workspace.
   */
  const signIn = useCallback(
    async (username, password, rememberMe = false) =>
      remember(await authApi.login(username, password, rememberMe)),
    [remember],
  )

  /**
   * The separate administration door at /admin/login.
   *
   * Kept for operators who bookmark it. It posts to the dedicated endpoint,
   * which refuses any account that is not a platform administrator.
   */
  const platformSignIn = useCallback(
    async (username, password) => remember(await authApi.platformLogin(username, password)),
    [remember],
  )

  const register = useCallback(async (body) => remember(await authApi.register(body)), [remember])

  /** Refreshes the in-memory user & institution snapshot from /api/v1/auth/me. */
  const refresh = useCallback(async () => {
    if (!tokenStore.get()) return null
    const session = await authApi.me()
    tokenStore.setUser(session.user)
    tokenStore.setInstitution(session.institution)
    setUser(session.user)
    setInstitution(session.institution)
    return session
  }, [])

  const signOut = useCallback(async () => {
    try {
      await authApi.logout()
    } catch {
      // The token may already be gone — the local session ends either way.
    }
    signOutLocally()
  }, [signOutLocally])

  const value = useMemo(
    () => ({
      user,
      institution,
      checking,
      signIn,
      signOut,
      register,
      platformSignIn,
      refresh,
      isAdmin: user?.role === 'ADMIN',
      isPlatformAdmin: user?.role === 'PLATFORM_ADMIN',
    }),
    [user, institution, checking, signIn, signOut, register, platformSignIn, refresh],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside an AuthProvider')
  return context
}
