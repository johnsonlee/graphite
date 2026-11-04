// The release build supplies files embedded in the standalone executable.
// A source checkout uses the adjacent package.json and installed TypeScript libraries.
export const distribution: {
  version: string;
  libraries: string[];
  notices: string;
} | undefined = undefined;
