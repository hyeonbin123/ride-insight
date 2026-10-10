import { create } from "zustand";

/**
 * The athlete the page is working with. The id lives in localStorage so a reload keeps the
 * selection; everything else comes from the API.
 */
interface AthleteState {
  athleteId: string | null;
  setAthleteId: (id: string | null) => void;
}

const KEY = "ride-insight.athleteId";

function readStored(): string | null {
  try {
    return window.localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export const useAthleteStore = create<AthleteState>((set) => ({
  athleteId: readStored(),
  setAthleteId: (id) => {
    try {
      if (id) window.localStorage.setItem(KEY, id);
      else window.localStorage.removeItem(KEY);
    } catch {
      // storage unavailable: keep the selection in memory only
    }
    set({ athleteId: id });
  },
}));
