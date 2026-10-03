**Claude Code Review**
Review completed at 2026-10-03T15:04:05Z UTC

<details open>
<summary><b>📝 Code Review</b> (click to expand)</summary>

**Adds bulk export of notes**

### 📊 Summary
- New `ExportController` with a CSV endpoint
- Front-end button on the notes page

*Files examined: 9*

### 🔍 Critical Findings
- **`Pocket.Api/Controllers/ExportController.cs:42-58`** — The export endpoint has no `[Authorize]` attribute, so any anonymous caller can download every user's notes.

```
[HttpGet("export")]
public async Task&lt;IActionResult&gt; Export(List&lt;int&gt; ids)
- var rows = await _db.Notes.ToListAsync();
```


  **Fix:** Add `[Authorize(Roles = "Admin")]` to the action and filter `rows` by the caller's team.

### ⚠️ Important Suggestions
- **`Pocket.Api/Services/CsvWriter.cs:17`** ⚠️ — Values are not escaped, so a comma or quote in a name breaks the CSV &lt;Generic&gt; rows.

  *Impact:* Corrupted exports for users with commas in their names.

  Quote every field and double embedded quotes (RFC 4180).

- **`src/pages/Notes.tsx`** ❓ — The export button stays enabled while a download is running.

  Disable it until the request settles.

### 💡 Minor Improvements
*No minor improvements to suggest.*

### ✅ Testing Recommendations
- **Integration test:** An anonymous request to `/export` must return 401.
- **Edge case test:** A name containing a comma and a quote round-trips through the CSV.

### 📈 Performance Notes
- **`Pocket.Api/Controllers/ExportController.cs`** — Loads every note row into memory; stream the query instead.

### 🎯 Verdict
**FAIL**

*The export endpoint is reachable without authentication.*

---
*Review completed using Claude AI with structured output validation.*

</details>

*Note: Context-aware full-stack review was not performed (no matching branches found in related repositories)*