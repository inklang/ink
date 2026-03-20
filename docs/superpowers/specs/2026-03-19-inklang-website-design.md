# inklang Website Design

## Context

inklang is a compiled scripting language (formerly lectern, then quill) targeting a register-based bytecode VM. The package manager is called quill. A rebrand from quill to ink is underway.

Goal: build a public website for inklang at github.com/inklang/inklang (GitHub Pages) to serve as the landing page, documentation, blog, and interactive playground.

**Constraints from user:**
- shadcn/ui (basic, clean)
- No runtime connected yet (playground is UI-only, API stub)
- Simple — landing, playground, docs, blog

---

## Stack

| Concern | Choice |
|---------|--------|
| Framework | Next.js 14 (App Router) |
| UI | shadcn/ui + Tailwind CSS |
| Fonts | Inter |
| Code editor | Monaco Editor (@monaco-editor/react) |
| Docs | Next.js App Router pages (`/docs/...`) with MDX |
| Blog | Next.js App Router (`/blog/...`) with MDX |
| Hosting | GitHub Pages — `out/` directory committed to repo |
| Deployment | `next export` to `out/`, GitHub Actions or manual |

---

## Visual Design

**Theme:** Dark (default), minimal
**Accent color:** Violet/indigo (shadcn default `--primary` range)
**Background:** `#09090b` (zinc-950)
**Text:** `#fafafa` / `#a1a1aa` (zinc-400 for muted)
**Components used:** Card, Button, Tabs, Accordion, Input, Separator

**Responsive breakpoints:**
- Mobile (`< md`): stacked layouts, full-width editor, collapsible sidebar
- Tablet (`md`): two-column landing, sidebar visible
- Desktop (`lg+`): full layout with persistent sidebar on docs

No custom animations — only Tailwind transitions (`transition-colors`, `hover:`).

---

## Pages

### `/` — Landing Page

**Hero section:**
- Large "inklang" wordmark (bold, clean font)
- Tagline: "A compiled scripting language for the modern VM."
- One-liner: "Fast. Simple. Extensible."

**Code preview:**
- Syntax-highlighted inklang code snippet in a card
- Example showing basic syntax (fn, print, etc.)

**CTAs:**
- "Get Started" → `/docs`
- "Try Playground" → `/playground`

**Features section:**
- 3-4 bullet points: compiled to bytecode, simple syntax, first-class functions, extensible via packages

**Footer:**
- Links: GitHub (github.com/inklang/inklang), (placeholder Discord), copyright

### `/playground` — Interactive Playground

**Layout:**
- Monaco editor (top, ~60% height)
- "Run" button (posts to `/api/run`, currently stubbed — returns mock output)
- Output panel below (stdout / stderr tabs)

**Behavior:**
- "Run" button sends code to `/api/run` (stubbed — returns `{ stdout: "...", stderr: "" }`)
- Example snippets dropdown to load pre-baked inklang code
- Copy-to-clipboard button on code editor
- (Runtime integration deferred — API returns mock data for now)

**Error handling (deferred to runtime integration):**
- Execution timeout: 5s limit (client-side timeout display if exceeded)
- Memory limit: enforced server-side (512MB suggested)
- Infinite loop: server-side timeout kills execution
- Malformed code: returned as stderr output

**Examples to include:**
- Hello world
- Simple function
- For loop with array

### `/docs` — Documentation

**Sidebar navigation** (left, collapsible on mobile):
- Introduction
- Getting Started
- Language Reference
- Standard Library
- Examples

**MDX files** in `/src/app/docs/`:
- `intro.mdx`, `getting-started.mdx`, `language-reference.mdx`, `stdlib.mdx`, `examples.mdx`

**Styling:** prose (Tailwind Typography plugin), code blocks with syntax highlighting via rehype-pretty-code or shiki

### `/blog` — Blog

**Index page** (`/blog`):
- List of posts: title, date, excerpt
- Sorted by date descending

**Post pages** (`/blog/[slug]`):
- MDX rendered post
- Frontmatter: `title`, `date`, `author`, `excerpt`

**Initial post:** `introducing-inklang.mdx` — brief announcement post about the rebrand

---

## Project Structure

```
inklang/
├── out/                    # GitHub Pages output (committed)
├── src/
│   ├── app/
│   │   ├── layout.tsx
│   │   ├── page.tsx        # Landing
│   │   ├── playground/
│   │   │   └── page.tsx
│   │   ├── docs/
│   │   │   ├── layout.tsx  # Docs sidebar layout
│   │   │   ├── page.tsx    # Redirect or intro
│   │   │   └── [...slug]/
│   │   ├── blog/
│   │   │   ├── page.tsx
│   │   │   └── [slug]/
│   │   └── api/
│   │       └── run/
│   │           └── route.ts  # Stub: returns mock output
│   ├── components/
│   │   ├── ui/             # shadcn components
│   │   ├── nav.tsx
│   │   ├── footer.tsx
│   │   ├── code-preview.tsx
│   │   └── playground/
│   │       ├── editor.tsx
│   │       └── output.tsx
│   └── lib/
│       └── utils.ts
├── public/
├── docs/                   # MDX content
│   ├── intro.mdx
│   ├── getting-started.mdx
│   ├── language-reference.mdx
│   ├── stdlib.mdx
│   └── examples.mdx
├── blog/                   # Blog MDX content
│   └── introducing-inklang.mdx
├── next.config.js
├── tailwind.config.ts
├── components.json         # shadcn init
└── package.json
```

---

## Implementation Order

1. Scaffold Next.js + shadcn + Tailwind
2. Landing page (`/`)
3. Docs with sidebar (`/docs`)
4. Blog (`/blog`)
5. Playground UI (`/playground`)
6. Stub API route (`/api/run`)
7. GitHub Pages deployment setup
8. Initial docs + blog content

---

## Open Questions

1. **GitHub org** — github.com/inklang org must be created before the repo can be made
2. **Tagline** — user to confirm final tagline
3. **Initial docs content** — sourced from ARCHITECTURE.md and existing lectern docs, need to know how much to port vs rewrite
4. **Playground backend** — API stub returns mock output; actual runtime integration is follow-up work
5. **Package manager name** — confirm whether quill keeps its name or is renamed under the ink org

---

## GitHub Setup Steps

1. Create github.com/inklang organization (user does this manually)
2. Create repo github.com/inklang/inklang (empty)
3. Add as remote and push site code
4. Enable GitHub Pages → deploy from `main` / `out` folder
