import type { Material, Role, StylePack, WeightedBlock } from '../content/types.js';
import { ROLE_FALLBACK } from '../content/types.js';
import { Rng } from '../util/rng.js';
import {
  bareId, composeBlock, inferVariant, parseBlock, withStates, type Facing,
} from './blockstate.js';

export interface BlockOptions {
  variant?: string;
  facing?: Facing;
  half?: 'top' | 'bottom';
  axis?: 'x' | 'y' | 'z';
  waterlogged?: boolean;
  states?: Record<string, string | number | boolean>;
}

const FACING_FAMILIES =
  /(_stairs|_door|_trapdoor|_fence_gate|_wall_sign|_wall_torch|_wall_banner|_wall_fan|_glazed_terracotta|_button|_hanging_sign|_head|_skull|_bed|_campfire|_lectern|_grindstone|_stonecutter|_loom|_anvil|_chest|_furnace|_smoker|_blast_furnace|_beehive|_bee_nest|_jack_o_lantern|_carved_pumpkin|_end_portal_frame|_piston|_dropper|_dispenser|_observer|_barrel|_lightning_rod|_amethyst_cluster|ladder|chest|furnace|anvil|loom|lectern|barrel|beehive|jack_o_lantern|carved_pumpkin|observer)$/;

const WATERLOGGABLE =
  /(_stairs|_slab|_fence|_wall|_pane|_trapdoor|_sign|_bars|_chain|_ladder|_lantern|_amethyst|_coral_fan|_campfire|_scaffolding)$/;

function supportsFacing(id: string): boolean {
  return FACING_FAMILIES.test(bareId(id));
}

function supportsWaterlog(id: string): boolean {
  const b = bareId(id);
  return WATERLOGGABLE.test(b) || b === 'chain' || b === 'iron_bars' || b === 'lantern' || b === 'ladder';
}

/**
 * Resolves abstract palette roles ("wall_primary", "roof_primary") into concrete
 * Minecraft block states for a given style. This is the piece that lets one
 * blueprint render as a medieval cottage, a winter cabin or a desert adobe house.
 */
export class Palette {
  readonly warnings = new Set<string>();

  constructor(
    readonly style: StylePack,
    private rng: Rng,
    /** role -> literal block; overrides the style for one call. */
    private overrides: Record<string, string> = {},
  ) {}

  /** Walks the role fallback chain declared in docs/SCHEMAS.md. */
  material(role: string): Material | undefined {
    const override = this.overrides[role];
    if (override) return { full: [{ block: override }] };

    let current: string | undefined = role;
    const seen = new Set<string>();
    while (current && !seen.has(current)) {
      seen.add(current);
      const mat = this.style.materials?.[current];
      if (mat && Array.isArray(mat.full) && mat.full.length) return mat;
      current = ROLE_FALLBACK[current as Role];
    }
    return undefined;
  }

  hasRole(role: string): boolean {
    return !!this.material(role);
  }

  /** A weighted pick from the role's `full` list, with no extra states applied. */
  fullBlock(role: string): string {
    const mat = this.material(role);
    if (!mat) {
      this.warnings.add(`style "${this.style.id}" has no material for role "${role}" — used stone`);
      return 'minecraft:stone';
    }
    const picked: WeightedBlock = this.rng.weighted(mat.full);
    return picked.block;
  }

  /** Full resolution: role + variant + orientation -> concrete block state string. */
  block(role: string, opts: BlockOptions = {}): string {
    const mat = this.material(role);
    const base = this.fullBlock(role);
    const variant = opts.variant && opts.variant !== 'full' ? opts.variant : undefined;

    let block = base;
    if (variant) {
      const explicit = mat && typeof mat[variant] === 'string' ? (mat[variant] as string) : undefined;
      const derived = explicit ?? inferVariant(base, variant);
      if (derived) {
        block = derived;
      } else {
        this.warnings.add(
          `style "${this.style.id}": role "${role}" has no "${variant}" variant and none could be derived from ${base} — used the full block`,
        );
      }
    }

    return this.applyStates(block, variant, opts);
  }

  private applyStates(block: string, variant: string | undefined, opts: BlockOptions): string {
    const parsed = parseBlock(block);
    const states: Record<string, string | number | boolean> = { ...parsed.states };
    const id = parsed.id;

    switch (variant) {
      case 'stairs':
        states.facing = opts.facing && opts.facing !== 'up' && opts.facing !== 'down' ? opts.facing : states.facing ?? 'north';
        states.half = opts.half === 'top' ? 'top' : states.half ?? 'bottom';
        states.shape = states.shape ?? 'straight';
        break;
      case 'slab':
        states.type = opts.half === 'top' ? 'top' : states.type ?? 'bottom';
        break;
      case 'door':
        states.facing = opts.facing ?? states.facing ?? 'north';
        states.half = 'lower';
        states.hinge = states.hinge ?? 'left';
        states.open = states.open ?? 'false';
        break;
      case 'trapdoor':
        states.facing = opts.facing ?? states.facing ?? 'north';
        states.half = opts.half === 'top' ? 'top' : states.half ?? 'bottom';
        states.open = states.open ?? 'false';
        break;
      case 'gate':
      case 'wall_sign':
        states.facing = opts.facing ?? states.facing ?? 'north';
        break;
      case 'axisX':
        states.axis = 'x';
        break;
      case 'axisY':
        states.axis = 'y';
        break;
      case 'axisZ':
        states.axis = 'z';
        break;
      case 'hanging':
        if (bareId(id).endsWith('lantern')) states.hanging = 'true';
        break;
      default:
        if (opts.facing && supportsFacing(id)) states.facing = opts.facing;
        if (opts.axis && /(_log|_wood|_stem|_hyphae|_pillar|bone_block|basalt|muddy_mangrove_roots|purpur_pillar)$/.test(bareId(id))) {
          states.axis = opts.axis;
        }
        break;
    }

    if (opts.waterlogged && supportsWaterlog(id)) states.waterlogged = 'true';
    if (opts.states) Object.assign(states, opts.states);

    return composeBlock(id, states);
  }

  /** Ground / path / plaza lists live outside `materials`; fall back to roles. */
  ground(kind: 'top' | 'under' | 'path' | 'pathEdge' | 'plaza'): string {
    const list = this.style.ground?.[kind];
    if (list && list.length) return this.rng.weighted(list).block;
    const roleMap: Record<string, string> = {
      top: 'ground_top', under: 'ground_under', path: 'path_primary',
      pathEdge: 'path_secondary', plaza: 'plaza',
    };
    return this.fullBlock(roleMap[kind]);
  }

  /** Turn a plain block into its waterlogged form where the block supports it. */
  static waterlog(block: string): string {
    return supportsWaterlog(block) ? withStates(block, { waterlogged: 'true' }) : block;
  }
}
