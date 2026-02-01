# From Typing Code to Designing Systems In a Month

I've been programming for almost 15 years now, last 5 on mission-critical software in the finance domain. I'm not some
script kiddie who builds things only for himself. I know the stakes, tradeoffs and implications. Yet for the last two
months I went full-throttle
AI-assisted coding across personal projects, OSS work, and professional tasks.

## The Turning Point

I've been playing with agentic coding since the Junie release around May 2025. The results were varying - sometimes
great, sometimes completely useless. Everything changed around December when the tools got significantly better for me.
Now I
use mostly Claude Code, occasionally Junie. I tried Codex but it was completely subpar.

// those 2 parapgraphs dont fit the headline. are somewhat weird. first is "how I do things" second fits summary better.
I would keep both but not here and not in this form

Here's the thing: I never bother with prompt crafting. Over time I've built some intuition about what to mention, but
I'm not doing it explicitly. I just write what I need and hope for the best. Instructions from CLAUDE.md are notoriously
ignored. Instructions from /plan as well. I don't use MCPs, plugins, skills, sub-agents or any of that. They're too
non-deterministic and bleeding edge. I put a lot of effort into eliminating noise in my life in general, and those tools
fall into the noise category for now. I'll start using them when they give significant leverage.

And here's my broader philosophy: don't follow the hype. AI is here to stay and it's transforming how we build software,
but don't jump on every new thing. I ignored Copilot, Cursor, Google Gemini, early Claude Code and other tools for a
long time. I'm currently ignoring Moltbook (OpenClaw). You have limited time and resources. Using tools when they
become "boring" - mainstream, proven - is usually the most effective strategy.

## Scala Works Fine, Thanks for Asking

One thing people were mentioning last year is that AI struggles with niche language, including Scala. It doesn't. At
least not with how I use it.

Scala 3 syntax? No problems. Implicits, type-level programming, macros? No significant issues. Cross-compilation between
JVM and JS? Works fine. sbt? Works fine. I asked it to learn Laminar from the docs - it found Waypoint on its own and
didn't struggle with it at all. I've thrown obscure libraries at it like decisions4s and it handles them pretty well.
Having checked-out sources around helps but isn't necessary. It just goes to docs or GitHub if it doesn't know
something.

I don't use significant whitespace and don't plan to. I've heard AI can get confused with it but never tested this
myself. It for sure doesn't get confused with good old curly braces.

## Harness, Not Prompts

If there's one thing I want you to take away from this article, it's this: testing harness is the most important thing
for vibe-coding. Not prompt engineering. Not fancy plugins. Harness.

I'm calling it harness because it's not only tests. It's tests, types, linters, and any other automated checks you can
put in
place. The more you rely on AI, the more harness you need. AI has a blindspot: it's very much local-reasoning-focused
and can easily miss the big picture. Integration tests are your guardrails.

For anything UI-related, e2e testing is a blessing. AI can handle writing Selenium tests perfectly well, and it gives it
huge autonomy in fixing its own mistakes. When I built SSBudget (more on it below), developing e2e tests at the very
beginning was one of
the best decisions I made. Bugs happened - of course they did. So I just made a list of bugs, gave it to
Claude, ask to cover them with tests when applicable and fix. Done.

## The Review Problem

Let me be blunt: reviews are a real PITA, and their diligence has become a function of code importance. This is probably
the biggest bottleneck right now.

For UI code? If it looks OK in the browser, I don't give a crap how it's expressed under the hood. I couldn't write
good frontend code in
the first place, so I can't meaningfully review it either. I just accept it and move on (at least the html part). For
OSS I use CodeRabbit and it
helps a lot with PR traffic. At work I've used Claude for semi-manual reviews - it's OK but not enough.

My current impression is that ~50% of AI output needs major
refinements or followups, and only ~40% is OK with minor changes (the remaining ~10% is total throwaway), the review
burden adds up fast.

## Are you delivering?

A lot of people praise the AI revolution without any hard data to back it up. So below is what I was able to do in the
last
month or so.

**SSBuget - personal budget tracker:**

I've built it in a week of evenings while playing Brotato. Fully
vibecoded. Tech stack: Scala 3, Laminar, Tapir, Passkeys, Selenium, SQLite.

What did AI handle? Everything, really.

* Backend - my comfort zone, handled fine.
* Frontend with Laminar - stuff I can barely do.
* Passkey authentication - stuff I definitely couldn't do without significant learning; just knowing where to
  start would have taken me days.
* Selenium tests - stuff I would never do because it's a pain in the ass.
* It even handled video creation with banners and pauses for the promo. (You can see it in readme)

**OSS (business4s ecosystem):**

- decisions4s: persistence support
- workflows4s: idempotent signals, linter
- forms4s: datatables, server mode, query params

All of those were significant developments that were waiting to be done for months. And for the first two projects I
keep the quality abr quite high

**Personal projects:**

- SSBudget described above
- My personal reading tooling: transformed string-and-tape Python scripts into a Flask app in a few days. I never used
  Python for anything bigger before.

**Work:**

A lot of tasks, but with much more nuance when it comes to AI usage - maybe 50% of OSS effectiveness.

## Design, Not Just Coding

Whats equally important to my AI usage: **I use it for design equally with coding**. I ask it to brainstorm ideas, plan
work, refine technical details and architecture.
The output design docs are good for
both humans and for later agentic coding
sessions. But here's the thing - design needs a lot more back-and-forth than actual coding. Significantly more
iteration. But once it's done, the implementation is rarely a problem.

I've planned not just particular features but also bigger multi-phase projects. One example from work: a feature that
required two prior enabler refactorings. AI helped plan the whole sequence. Another: exploring the revival of abtesstr
and chatOps4s - redefining goals, product-market-fit, APIs, architecture.

And the gain is not only quantitative but also qualitative - I've never been so diligent about my work. PReviously I
would just start coding stuff and see what comes. Now AI forces me to write my thoughts and plans down and it makes a
big difference.

## The Mindset Shift

Do I miss organic coding? Yes and no. I still write maybe 10-20% of code where problems are genuinely hard and AI can't
get it. But here's what changed: solving problems gives me much more satisfaction than typing ever did. The satisfaction
shifted from crafting code to designing systems.

I multiplied my effectiveness by leaps and bounds and the tradeoff is worth it.

But - and this is crucial - **knowing what to build became much more important than ever before**. I could easily build
stuff that makes no sense in the grand scheme of things. Producing code got very cheap, but every line of code is still
a cost to maintain. You really need to know what makes sense, what features are important, what is the long-term
stategy, what approaches to use, what will be the impact on the team and maintenance. Product-mindset and broad
perspective is key.

Here's my worry: without strong software engineering fundamentals - and I mean NOT CODING, I mean real long-term
engineering thinking - AI-assisted coding is a certain disaster. Everyone now has access to Ferraris, but the brakes and
controls are still in our heads.

## What Changed

The scope of what's possible expanded dramatically. Missing expertise is no longer a blocker - I would have never done
passkeys because of how much learning it required.

// this needs more emphasis, its a huge change for me
Available time slots are no longer a blocker either. AI allows me to
reuse small chunks of time. Historically I could only be productive in big batches of continuous focus. Now even 15
minutes is enough to get a few hours worth of outputs. 15 minutes between meetings at work or minutes when my familly
doesnt need me in the evening.

My workflow adapted: I try to have 2-3 AI-compatible tasks running all the time, so at least something is always in
progress. This sometimes requires multiple clones of the same repository when I'm working on two features
simultaneously.

One constant battle: verbosity. AI loves to be verbose - in docs, code comments, code itself. "Keep succinct", "
eliminate repetition", "remove redundant comments" are probably my most repeated phrases.

// This "Waht changed" paragraphs doesnt seem consistent. Maybe worth merging/rethinking with issues form the
begginning?

## Can You Actually Trust It?

You almost never can trust AI with anything. Everything comes down to reviews, harness, and diligence.

UI is my only exception - if it looks OK, I accept it. But I don't build UIs for work or for anything serious, so my bar
is low there. Anything else has to be treated with utmost care.

Technical debt? It will bury you if you don't keep things under control. Sensitive code and credentials? Good
habits and practices are more important than ever - if you kept your secrets in a repo before, you will have a problem
now.

## Where This Goes

I honestly don't know how people will get enough expertise to use AI reliably in the future. LEarning from more
experienced colleagues is now more important than ever. And leaving junior without supervision is more dangerous than
ever. The fundamentals that let
you judge
whether AI output makes sense - those still need to come from somewhere.

If you're a Scala developer on the fence about
AI tooling: it works, it's not hype, but it requires a different kind of vigilance than writing code yourself.

The shift is real: from typing code to designing systems. From crafting to directing. From coding sessions to shipping
sessions. I'm still adapting, but I'm shipping more than ever.
