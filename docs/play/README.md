# Google Play Console files

| File | Use |
| --- | --- |
| `data-safety.csv` | Data safety answers for the v1.0 release (Play Billing + licence server). Import under App content > Data safety > Import. |
| `data-safety-no-collection.csv` | Same form answered "collects no data", for test-track builds that ship before billing is wired in. |
| `data-safety-blank-export.csv` | The blank export the two files are generated from. |

Regenerate after a policy or feature change with `python3 tools/make_data_safety.py docs/play/data-safety-blank-export.csv docs/play`.

## What the v1.0 answers say

- Collects data: yes, encrypted in transit, no accounts, deletion requests via the privacy policy's "Deleting your data" section.
- **Purchase history** (the Google Play purchase token): collected, not ephemeral, optional (only when buying or restoring a pack), for app functionality and fraud prevention.
- **Device or other IDs** (the Play Integrity token): collected, ephemeral (verified and discarded), optional, for fraud prevention.
- Nothing is shared with third parties. Prompts sent to an AI provider go directly from the phone with the user's own key at the user's request, which Play's policy counts as a user-initiated transfer rather than sharing. Device scans, themes, imported images and fonts never leave the phone.

Privacy policy URL for the listing: https://bingblop.github.io/hexnext-site/privacy-policy.html
