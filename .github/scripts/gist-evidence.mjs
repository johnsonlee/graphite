import fs from "node:fs";
import path from "node:path";

export async function materializeGistFiles({ files, gistId, owner, directory, request }) {
    fs.mkdirSync(directory, { recursive: true });
    const contents = {};
    for (const [name, file] of Object.entries(files)) {
        if (path.basename(name) !== name) {
            throw new Error(`Evidence file has an unsafe name: ${name}`);
        }
        let content = file.content;
        if (file.truncated) {
            const raw = new URL(file.raw_url);
            const expectedPrefix = `/${owner}/${gistId}/raw/`;
            const suffix = `/${encodeURIComponent(name)}`;
            if (raw.protocol !== "https:" || raw.hostname !== "gist.githubusercontent.com" ||
                !raw.pathname.startsWith(expectedPrefix) || !raw.pathname.endsWith(suffix) ||
                !/^[/][0-9a-f]{40}[/]/.test(raw.pathname.slice(expectedPrefix.length - 1))) {
                throw new Error(`Evidence file ${name} has an invalid immutable raw URL`);
            }
            const response = await request({
                method: "GET",
                url: raw.toString(),
                headers: { accept: "application/vnd.github.raw" }
            });
            content = response.data;
        }
        if (typeof content !== "string") {
            throw new Error(`Evidence file ${name} is missing text content`);
        }
        fs.writeFileSync(path.join(directory, name), content);
        contents[name] = content;
    }
    return contents;
}
