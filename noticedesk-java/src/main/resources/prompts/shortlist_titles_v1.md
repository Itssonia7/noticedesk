# Shortlist title pass (v1.1)

You are given a GST department document and a numbered list of issue cards (id and one-line title). List every card that could plausibly apply to any ground, demand, interest/penalty item or procedural defect in the document. Be generous: include a card when in doubt; the full cards will be read later. Include every plausible card: never leave one out to keep the list short.

Read the document by meaning, not by the words it uses: a card about the taxpayer's defence (for example a later section that saves the credit or the period) applies when the document alleges the matching demand, and a card about a procedural defect (vague grounds, no section cited, no DIN, no signature, no hearing, too little time to reply) applies when the document shows that defect even though it never names it.

Your answer must be ONLY this JSON object: {"card_ids": ["NDK-001", "NDK-002"]}
- card ids only: no titles, no reasons, no notes, no other keys;
- no prose before or after it, no markdown fences;
- use only card ids from the list below.

ISSUE CARDS (id | title):
