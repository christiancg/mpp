export const FileKind = { FILE: "FILE", DIRECTORY: "DIRECTORY" } as const;
export type FileKind = (typeof FileKind)[keyof typeof FileKind];

export const FileWriteMode = { REPLACE: "REPLACE", CREATE: "CREATE", APPEND: "APPEND" } as const;
export type FileWriteMode = (typeof FileWriteMode)[keyof typeof FileWriteMode];

export interface FileWriteOptions {
    readonly mode?: FileWriteMode;
    readonly createParents?: boolean;
    readonly ifVersion?: string;
}

export interface FileListOptions {
    readonly recursive?: boolean;
    readonly pageSize?: number;
    readonly after?: string;
}

export interface FileDeleteOptions {
    readonly recursive?: boolean;
}

export interface FileMoveOptions {
    readonly overwrite?: boolean;
    readonly createParents?: boolean;
}

export interface FileWritten {
    readonly path: string;
    readonly version: string;
    readonly size: number;
}

export interface FileRange<C> {
    readonly path: string;
    readonly version: string;
    readonly size: number;
    readonly offset: number;
    readonly content: C;
    readonly eof: boolean;
}

export interface FileStat {
    readonly path: string;
    readonly kind: FileKind;
    readonly size: number;
    readonly version: string;
    readonly modifiedAt: number;
}

export interface FileMoved {
    readonly path: string;
    readonly version: string;
    readonly moved: number;
}

export interface DirectoryMade {
    readonly path: string;
    readonly version: string;
    readonly created: number;
}
