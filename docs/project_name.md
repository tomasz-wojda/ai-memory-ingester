# Project Naming Variations, Analysis & Proposals

## 1. Executive Summary

This document consolidates historical naming conventions found across the repository, evaluates the user-proposed name **`ming`** (**M**emory **ING**ester), and provides a structured taxonomy of naming propositions for the application, repository, and CLI binary executable.

---

## 2. Historical Names Found in Codebase & Commits

| Scope / Location | Name Used | Description / Context |
| :--- | :--- | :--- |
| **GitHub Repository** | `ai-memory-ingester` | Canonical GitHub repository identifier (`https://github.com/tomasz-wojda/ai-memory-ingester`). |
| **Workspace Folder** | `bscs-tools` | Root workspace folder name (`c:\workspace\bscs-tools`). |
| **Workspace Skill ID** | `archive-memory-context-engine` | Active Antigravity skill path (`.agents/skills/archive-memory-context-engine/SKILL.md`). |
| **CLI Banners / Headers** | `Archive Memory Context Engine` | User-facing CLI title in `run.groovy`, `01_analyze_archives.groovy`, `03_query_memory.groovy`. |
| **Test Suite Title** | `Memory Query Engine` | Automated test runner banner in `tests/test_query_suite.groovy`. |
| **Core Storage Class** | `MemoryEngine` | Primary relational + FTS5 SQLite abstraction in `lib/MemoryEngine.groovy`. |
| **Dataset Subsystem** | `DatasetRegistry` / `FederatedEngine` | Federation and dataset management layer. |
| **Conversation Target** | `worklog-chat` / `convo.db` | Chat interaction history dataset and database. |

---

## 3. The `ming` Concept Analysis

### Etymology
- **`ming`** = **M**emory **ING**ester / **M**emory **ING**estion **G**rid.
- Pronunciation: Single syllable, crisp, memorable (`/mɪŋ/`).

### CLI Ergonomics & Syntax Comparison
Using `ming` as the primary CLI wrapper (`ming <command>`) offers significant ergonomic advantages over `groovy run.groovy`:

```bash
# Current Syntax
groovy run.groovy query "BusinessPartner" --dataset default
groovy run.groovy ingest --dir fixtures/ --as sample
groovy run.groovy append --text "..." --archive worklog-chat
groovy run.groovy datasets

# With 'ming' CLI Binary
ming query "BusinessPartner"
ming ingest --dir fixtures/ --as sample
ming append --text "..." --dataset worklog-chat
ming datasets
ming use worklog-chat
ming recent 5
```

### Strengths of `ming`
1. **Ultra-Short (4 characters):** Fast to type in terminal sessions.
2. **Distinctive:** Distinct from generic system commands (`mem`, `find`, `query`).
3. **Dual Identity:** Functions seamlessly both as a binary executable command (`ming`) and a product identity (**MING Engine**).

---

## 4. Comprehensive Naming Taxonomy & Propositions

### Category A: Short CLI-First Names (1–4 characters)

| Name | Full Expansion | Pros | Cons |
| :--- | :--- | :--- | :--- |
| **`ming`** | **M**emory **ING**ester | Short, catchy, natural abbreviation, matches repo domain. | Might conflict with Ming compiler if installed in global PATH. |
| **`amce`** | **A**rchive **M**emory **C**ontext **E**ngine | Direct acronym of internal architecture banner. | Less phonetically intuitive than `ming`. |
| **`mctx`** | **M**emory **C**on**t**e**x**t | Clear engineering shorthand for AI Context. | Slightly technical/abrupt. |
| **`memi`** | **Mem**ory **I**ngester | Symmetrical, easy to remember. | Sounds like "meme". |
| **`mink`** | **M**emory **IN**gestion & **K**nowledge | Distinctive wordmark, fast typing. | Slang collision with animal/fur. |

---

### Category B: Descriptive & Repository-Level Names

| Name | GitHub Slug | Positioning |
| :--- | :--- | :--- |
| **AI Memory Ingester** | `ai-memory-ingester` | **Current Default.** Highly descriptive for GitHub search and SEO. Clear purpose for LLM/AI workflows. |
| **Archive Memory Context Engine** | `archive-memory-context-engine` | **Enterprise/Technical.** Exact description of capabilities (multi-GB archives $\rightarrow$ SQLite FTS5 context). |
| **MING: Memory Ingestion Engine** | `ming` or `ming-ai` | **Product/Brand.** Concise brand packaging for both the repo and CLI tool. |
| **ContextVault** | `context-vault` | **Security/Storage.** Emphasizes hermetic, offline, zero-leak local context storage. |
| **ConvoMem Engine** | `convomem` | **Conversational.** Emphasizes turn logging, session replay, and chat memory. |
| **Worklog Context Engine** | `worklog-context-engine` | **Productivity.** Emphasizes worklog tracking, interaction preservation, and IDE synchronization. |

---

### Category C: Hybrid & Component Layering

A layered naming strategy decouples the repository brand, the CLI executable, and internal subsystems:

```text
┌────────────────────────────────────────────────────────┐
│ Product / Repository Brand:                            │
│ AI Memory Ingester (ai-memory-ingester / ming)         │
├────────────────────────────────────────────────────────┤
│ CLI Command / Executable:                              │
│ ming (e.g. ming query, ming ingest, ming datasets)     │
├────────────────────────────────────────────────────────┤
│ Subsystem Modules:                                     │
│ • AMCE (Archive Memory Context Engine - Archives)     │
│ • ConvoMem / Worklog (Conversation & Chat Ingestion)   │
│ • FederatedEngine (Multi-DB RRF Fusion)                │
└────────────────────────────────────────────────────────┘
```

---

## 5. Summary Recommendation

1. **Repository & Project Name:** Keep or alias **`ai-memory-ingester`** (or **`ming`** / **`ming-memory-engine`**).
2. **CLI Executable & Script:** Adopt **`ming`** (`ming.bat` for Windows, `ming` shell script for Linux/macOS) delegating to `run.groovy`.
3. **Workspace Skill:** Retain or alias `.agents/skills/ming/` alongside `.agents/skills/archive-memory-context-engine/`.
4. **Conversation Dataset:** Adopt **`worklog-chat`** with database **`convo.db`**.
