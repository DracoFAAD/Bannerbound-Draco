# CivPM - Civilization Particle Manager
A performance based solution for high NPC counts, which makes *some NPCs* purely data based 
and entities only a client representation of them

---
## How it works
As of the current state, there are three major NPC groups:

1. Active Worker (Workers which are elligible to work)
2. Wanderer (NPCs which aren't working)
3. Special (Workers which can't be represented through data-only, like guards)

NPCs are assigned to one of those three groups, and based on that they go
through group-based optimization.

Aside from NPC groups, CivPM also introduces regions. Regions are 3x3 chunks 
where wanderers spend their majority of time in.

The magic behind the optimization lies in the fact, that we no longer make every NPC tick. CivPM handles most of the logic required for the Gameplay. 
So most NPCs don't add any tick overhead at all for the server!

---

## Won't it feel empty in the Client?

No! The pure data NPCs are represented through Entities on the client. They are spawned in on the client
only when they are actually supposed to see it (Based on which region they currently are in).
So even if you have 1000+ Civilians in your Civilization, you can fly around and still see them! How cool is that?