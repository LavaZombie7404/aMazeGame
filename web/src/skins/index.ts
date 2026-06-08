import type { Skin } from "./types";
import { mathTextbookSkin } from "./math-textbook";
import {
  geometryDashClassicSkin,
  geometryDashMeltdownSkin,
  geometryDashSubZeroSkin,
} from "./geometry-dash";
import { galaxySkin } from "./galaxy";

const REGISTRY: Record<string, Skin> = {
  [mathTextbookSkin.id]: mathTextbookSkin,
  [geometryDashClassicSkin.id]: geometryDashClassicSkin,
  [geometryDashSubZeroSkin.id]: geometryDashSubZeroSkin,
  [geometryDashMeltdownSkin.id]: geometryDashMeltdownSkin,
  [galaxySkin.id]: galaxySkin,
};

export const DEFAULT_SKIN_ID = mathTextbookSkin.id;

export function listSkins(): Skin[] {
  return Object.values(REGISTRY);
}

export function getSkin(id: string): Skin {
  return REGISTRY[id] ?? mathTextbookSkin;
}

export type { Skin, SkinMetrics, SkinPalette, TrailStyle } from "./types";
