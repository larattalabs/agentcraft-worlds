**Claude Code Review**
Review completed at 2026-10-03T15:04:05Z UTC

<details open>
<summary><b>📝 Code Review</b> (click to expand)</summary>

**Adds a tag filter to the note list view**

### 📊 Summary
- Adds a `TagFilter` component and wires it into `NoteList`
- Filters are kept in the URL query string

*Files examined: 6*

### 🔍 Critical Findings
*No critical issues found.*

### ⚠️ Important Suggestions
*No important suggestions.*

### 💡 Minor Improvements
- **`src/components/TagFilter.tsx`** — The `onChange` handler re-creates the tag array on every keystroke; `useMemo` would avoid re-renders of `NoteList`.
- Consider a short README note about the new `?tags=` query parameter.

### 📚 Teachable Moments
- **Derived state** — Copying props into state drifts out of sync.

  *Best practice:* Compute from props.

  *How to apply:* Drop `selectedTags` state; derive from the URL.

### ✅ Testing Recommendations
- **Unit test:** Cover an empty tag list and a tag with spaces in `parseTagQuery`.

### 🎯 Verdict
**PASS**

*Small, well-scoped change with tests; only minor suggestions.*

---
*Review completed using Claude AI with structured output validation.*

</details>

*Note: Context-aware full-stack review was not performed (no matching branches found in related repositories)*