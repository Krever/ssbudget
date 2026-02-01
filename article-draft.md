# From Typing Code to Designing Systems In a Month

## Proposed Structure

### 1. Introduction

- 15 years programming, last 5 on mission-critical software in finance
- Not some script kiddie building toys - I know the stakes
- Scope: personal projects, OSS work, professional work
- This is my honest assessment after two months of full-throttle AI-assisted coding

### 2. The Turning Point

- Playing with agentic coding since Junie release (May 2025)
- Results were varying - sometimes great, sometimes useless
- Everything changed around December when tools got significantly better
- Current toolset: mostly Claude Code, some Junie, tried Codex (completely subpar)

### 3. What AI Can Handle in the Scala World

- Scala 3 syntax: no problems at all
- No significant issues with implicits, type-level programming, macros
- Libraries: Laminar, Tapir, even obscure ones (decisions4s)
- Having sources around helps but is not necessary
- Goes to docs/GitHub if it doesn't know something
- Learning from docs on the fly (asked it to learn Laminar, it discovered Waypoint on its own)
- Cross-compilation (JVM + JS): no issues
- sbt: no issues
- Things you'd never bother doing yourself (Selenium tests, video "direction" with banners and pauses)
- I don't use significant whitespace and don't plan to - heard AI can get confused with it, never tested

### 4. The Testing Harness Thesis

- "Harness, not just tests" - types, linters, automated checks
- The more you rely on AI, the more harness you need
- E2E testing as the AI autonomy enabler - AI can fix its own mistakes when it can run tests
- AI's blindspot: local reasoning, misses the big picture easily
- Integration tests as guardrails even for non-UI code
- Developing Selenium tests at the very beginning was a blessing
- AI handles e2e test writing perfectly well

### 5. Using AI for Design, Not Just Coding

- Use AI for design equally with coding
- Design docs that work for humans AND future agentic coding
- More back-and-forth than actual coding - significantly more iteration needed
- Multi-phase project planning, not just single features
- Examples:
    - SSBudget: planned and built end-to-end with AI
    - Work feature that required 2 prior enabler refactorings - AI helped plan the sequence
    - Exploring revival of abtesstr (https://github.com/business4s/abtesstr)

### 6. The Review Problem (Biggest Bottleneck)

- This is the biggest bottleneck right now
- Reviews are a real PITA
- Review diligence became a function of code importance
- UI code: "if it looks OK, I don't care how it's expressed under the hood"
- The uncomfortable truth: I couldn't write frontend code in the first place, I can't review it either
- CodeRabbit for OSS - helps a lot with PR traffic
- Claude for semi-manual reviews at work - ok but not enough
- Code consistency across sessions: scalafmt handles it

### 7. Do I Miss Organic Coding?

- Yes and no
- Still write 10-20% of code where problems are genuinely hard and AI can't get it
- Solving problems gives me much more satisfaction than typing
- The satisfaction shift: from crafting code to solving problems
- I multiplied my effectiveness by leaps and bounds - the tradeoff is worth it

### 8. My Workflow (Practical Tips)

- No prompt crafting - over time built intuition of what to mention, but not doing it explicitly
- Just write and hope for the best
- Don't use MCP, plugins, skills, sub-agents (yet)
- Don't see special value in CLAUDE.md either - instructions are notoriously ignored anyway
- Even /plan instructions also often ignored
- These tools are too non-deterministic and bleeding edge
- Put a lot of effort to eliminate noise in life in general - these tools fall into noise category for now
- Will start using them when they give significant leverage
- Keep 2-3 AI-compatible tasks running always
- Sometimes requires multiple clones of the same repo to work on two features simultaneously
- Constantly fight verbosity: "keep succinct", "eliminate repetition", "remove redundant comments" - my most repeated phrases
- Bugs workflow: make a list, give it to Claude, ask to cover with tests when applicable, done
- Don't follow the hype:
    - AI is here to stay and transforming how we build software, but don't jump on every new thing
    - Ignored Copilot, Cursor, Google Gemini, early Claude Code and other tools for a long time
    - Currently ignoring Moltbook (OpenClaw)
    - You have limited time and resources
    - Using tools when they become "boring" (mainstream, proven) is usually the most effective strategy

### 9. The Product Mindset Shift

- Knowing what to build became much more important than ever before
- Could easily build stuff that makes no sense in the grand scheme of things
- Producing code got very cheap but every code line is still a cost
- You really need to know what makes sense
- Product-mindset and broad perspective is key
- Without strong software engineering fundamentals (NOT CODING! real long-term engineering), AI-coding is a certain disaster
- Everyone now has access to Ferraris but brakes and controls are still in our heads

### 10. Concrete Example: SSBudget

- Personal budget tracker, fully vibecoded
- Built in a week of evenings (while playing Brotato)
- Tech stack: Scala 3, Laminar, Tapir, Passkeys, Selenium, SQLite
- No prompt crafting even here
- What AI handled:
    - Backend (my comfort zone) - handled fine
    - Frontend with Laminar (stuff I can barely do) - handled fine
    - Passkey authentication (stuff I definitely couldn't do without significant learning) - handled fine
    - Selenium tests (stuff I would never do because it's a pain in the ass) - handled fine
    - Even promo video creation with banners and pauses
- Passkeys specifically: I would have never done it, just knowing where to start would take me days
- Bugs happened: list them, give to Claude, ask for test coverage, done

### 11. Changed Scope of What's Possible

- Can do much more now - not only in terms of outputs
- Missing expertise no longer a blocker
- Available time slots no longer a blocker
- AI allows reusing small slots of time
- Historically: could only be productive in big batches of continuous focus
- Now: even 15 minutes is enough to get few hours worth of outputs
- Would have never done passkeys - too much learning required

### 12. The Proof: Are You Actually Shipping?

- A lot of people praise AI but can't really pinpoint the increase in productivity. I can.
- OSS (business4s ecosystem):
    - decisions4s: persistence support
    - workflows4s: idempotent signals, linter
    - forms4s: datatables, server mode, query params support
- Personal projects:
    - ssbudget: whole app in a week of evenings
    - My personal reading tooling: transformed string-and-tape scripts into a Flask app in a few days (also refreshed my Python skills - never used Python for anything bigger before)
- Work: a lot of tasks but with much more nuance when it comes to AI usage - maybe 50% of OSS effectiveness

### 13. Can You Trust AI?

- Failure rate: ~10% total throwaway, ~50% needs major refinements/followups, ~40% ok or minor changes
- Everything comes down to reviews, harness, and diligence
- You almost never can trust AI with anything
- UI is my only exception - if it looks ok, I accept it. Caveat: I don't build UIs for work/seriously.
- Technical debt: no, if you keep it under control
- Sensitive code/credentials: good habits and practices are more important than ever

### 14. Conclusion

- Where this is going
- How will people get enough expertise to use AI reliably?
- Advice for Scala developers considering AI tooling

---

# Rewrite Guide – Transform Notes into a Cohesive Article

## Goal
Turn raw notes into a narrative, opinionated, experience-driven article.

## Core Principles

- Slight tendency toward **story over inventory** but don't obses on it. Its technical in the end
- Prefer **sections and paragraphs over many small headings**
- Keep my style from original notes.md

---

## Content Guidelines

### Emphasize
- Testing harness thesis
- Review fatigue & trust issues
- Real examples and stories
- Mindset/product thinking shifts

### Reduce/compress
- Repetitive “handled fine” statements

---

## Success Criteria

After rewriting:
- Flows like a story
- Feels cohesive
- Doesnt feel like marketing or over-polished article
- Memorable key ideas (“Harness, not prompts”, “coding is cheap, thinking is expensive”)
