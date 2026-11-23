// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

"use client";

import { useState, useCallback, useEffect, useMemo, useRef } from "react";
import { useLocale, useTranslations } from "next-intl";
import {
  ReactFlow,
  Background,
  Controls,
  applyNodeChanges,
  applyEdgeChanges,
  addEdge,
  type Node,
  type Edge,
  type NodeChange,
  type EdgeChange,
  type Connection,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import {
  ArrowLeft,
  Check,
  ChevronDown,
  Code,
  Languages,
  LayoutTemplate,
  List as ListIcon,
  Loader2,
  Moon,
  MoreHorizontal,
  Pencil,
  Play,
  Save,
  Share2,
  Sun,
  Workflow,
} from "lucide-react";

import type { BizAppManifest, BizAppEntity, BizAppBlock } from "@/types";
import { usePathname, useRouter } from "@/i18n/navigation";
import { useApiClient } from "@/hooks/useApiClient";
import { useAuthStore } from "@/components/providers/StoreProvider";
import { listWorkspacesApi } from "@/lib/workspace-api";
import { useTheme, type Theme } from "@/hooks/useTheme";
import { transitionTheme } from "@/lib/theme-transition";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { NodeEntity } from "./NodeEntity";
import { NodeBlock } from "./NodeBlock";
import { Palette } from "./Palette";
import { Inspector } from "./Inspector";
import { reportViewsOf, syncReportViews } from "@/lib/bizapp-report";
import { removeBlockFromManifest, removeEntityFromManifest } from "@/lib/bizapp-manifest-edit";
import { composerShortcut } from "@/lib/composer-shortcuts";
import { defaultBlockParams, isHookableBlockType } from "@/lib/bizapp-block";
import { LIFECYCLE_HOOKS, nextHookLabel } from "@/lib/bizapp-hooks";
import { useComposerHistory } from "./use-composer-history";
import { useManifestValidation } from "./use-manifest-validation";
import { ValidationPanel } from "./ValidationPanel";
import { JsonTab } from "./JsonTab";
import { ListView } from "./ListView";
import {
  ComposerValidationContext,
  validationErrorsByNode,
} from "./validation-context";
import {
  manifestToGraph,
  graphToManifest,
  layoutGraph,
  type EntityNodeData,
  type BlockNodeData,
} from "./graph-manifest";

interface ComposerCanvasProps {
  initialManifest: BizAppManifest;
  draftId?: string;
  onSaveDraft?: (manifest: BizAppManifest) => Promise<void>;
  onSaveTemplate?: (manifest: BizAppManifest) => Promise<void>;
  onInstall?: (manifest: BizAppManifest) => Promise<void>;
  onBack?: () => void;
}

export function ComposerCanvas({
  initialManifest,
  onSaveDraft,
  onSaveTemplate,
  onInstall,
  onBack,
}: ComposerCanvasProps) {
  const t = useTranslations("BizApps.composer");
  const locale = useLocale();
  const pathname = usePathname();
  const router = useRouter();
  const { request } = useApiClient();
  const user = useAuthStore((state) => state.user);
  const [workspaceName, setWorkspaceName] = useState<string | null>(null);
  const { theme, setTheme } = useTheme();

  const changeTheme = (nextTheme: Theme) => {
    transitionTheme(() => setTheme(nextTheme));
  };

  const handleBack = () => {
    if (onBack) {
      onBack();
    } else {
      router.push("/admin/apps");
    }
  };
  const [activeTab, setActiveTab] = useState<"canvas" | "list" | "json">("canvas");
  const {
    manifest,
    revision: historyRevision,
    setManifest,
    replaceManifest,
    commitSnapshot,
    undo,
    redo,
    canUndo,
    canRedo,
  } = useComposerHistory<BizAppManifest>(initialManifest);
  const dragStartManifest = useRef<BizAppManifest | null>(null);
  const appliedHistoryRevision = useRef(0);

  const [nodes, setNodes] = useState<Node[]>(() => manifestToGraph(initialManifest).nodes);
  const [edges, setEdges] = useState<Edge[]>(() => manifestToGraph(initialManifest).edges);

  const [selectedNode, setSelectedNode] = useState<{
    type: "entity" | "block";
    data: BizAppEntity | BizAppBlock;
  } | null>(null);
  const [edgeHook, setEdgeHook] = useState<Edge | null>(null);

  useEffect(() => {
    if (historyRevision === appliedHistoryRevision.current || historyRevision === 0) return;
    appliedHistoryRevision.current = historyRevision;
    const graph = manifestToGraph(manifest);
    setNodes(graph.nodes);
    setEdges(graph.edges);
    setSelectedNode(null);
    setEdgeHook(null);
  }, [historyRevision, manifest]);

  const {
    isValid,
    isValidating,
    errors: validationErrors,
    issues: validationIssues,
  } = useManifestValidation(manifest);
  const [isSaving, setIsSaving] = useState(false);
  const nodeErrors = useMemo(
    () => validationErrorsByNode(manifest, validationIssues),
    [manifest, validationIssues],
  );
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameVi, setRenameVi] = useState("");
  const [renameEn, setRenameEn] = useState("");

  useEffect(() => {
    let active = true;
    void listWorkspacesApi(request)
      .then((workspaces) => {
        if (!active) return;
        const current = workspaces.find((workspace) => workspace.id === user?.activeWorkspaceId);
        setWorkspaceName(current?.name ?? null);
      })
      .catch(() => {
        if (active) setWorkspaceName(null);
      });
    return () => {
      active = false;
    };
  }, [request, user?.activeWorkspaceId]);

  const displayNameVi = manifest.name?.vi || manifest.name?.en || manifest.kind;
  const displayNameEn = manifest.name?.en || manifest.name?.vi || manifest.kind;

  const openRename = () => {
    setRenameVi(String(displayNameVi));
    setRenameEn(String(displayNameEn));
    setRenameOpen(true);
  };

  const applyRename = () => {
    const nextVi = renameVi.trim();
    const nextEn = renameEn.trim();
    if (!nextVi || !nextEn) return;
    setManifest((current) => ({
      ...current,
      name: { vi: nextVi, en: nextEn },
    }));
    setRenameOpen(false);
  };

  const nodeTypes = useMemo(() => ({
    entityNode: NodeEntity,
    blockNode: NodeBlock,
  }), []);

  const viewOptions = [
    { id: "canvas" as const, label: t("canvasTab"), Icon: Workflow },
    { id: "list" as const, label: t("listTab"), Icon: ListIcon },
    { id: "json" as const, label: t("jsonTab"), Icon: Code },
  ];
  const currentView = viewOptions.find((view) => view.id === activeTab) ?? viewOptions[0];

  // Synchronize graph to manifest. Layout-only changes replace the present
  // snapshot; discrete edits pass `record` so undo has a useful step.
  const syncToManifest = useCallback((
    currentNodes: Node[],
    currentEdges: Edge[],
    record = false,
  ) => {
    const updated = graphToManifest(currentNodes, currentEdges, manifest);
    if (record) {
      setManifest(updated);
    } else {
      replaceManifest(updated);
    }
  }, [manifest, replaceManifest, setManifest]);

  const onNodesChange = useCallback((changes: NodeChange[]) => {
    setNodes((nds) => {
      const updated = applyNodeChanges(changes, nds);
      if (changes.some((change) =>
        change.type === "position" || change.type === "remove" || change.type === "add"
        || change.type === "replace"
      )) {
        syncToManifest(updated, edges);
      }
      return updated;
    });
  }, [edges, syncToManifest]);

  const onEdgesChange = useCallback((changes: EdgeChange[]) => {
    setEdges((eds) => {
      const updated = applyEdgeChanges(changes, eds);
      if (changes.some((change) => change.type === "remove" || change.type === "add"
        || change.type === "replace")) {
        syncToManifest(nodes, updated);
      }
      return updated;
    });
  }, [nodes, syncToManifest]);

  const onNodeDragStart = useCallback(() => {
    dragStartManifest.current = manifest;
  }, [manifest]);

  const onNodeDragStop = useCallback(() => {
    if (dragStartManifest.current) {
      commitSnapshot(dragStartManifest.current);
      dragStartManifest.current = null;
    }
  }, [commitSnapshot]);

  const onConnect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target) return;
    const source = nodes.find((node) => node.id === connection.source);
    const target = nodes.find((node) => node.id === connection.target);
    if (source?.type !== "entityNode" || target?.type !== "blockNode") return;
    const targetBlock = (target.data as BlockNodeData).block;
    if (!isHookableBlockType(targetBlock?.type)) return;
    setEdges((current) => {
      const existing = current.filter(
        (edge) => edge.source === connection.source && edge.target === connection.target,
      );
      const hook = nextHookLabel(existing.map((edge) => String(edge.label)));
      if (!hook) return current;
      const next = addEdge({
        ...connection,
        id: `hook-${connection.source}-${connection.target}-${hook}-${Date.now()}`,
        label: hook,
        type: "smoothstep",
        data: { kind: "hook" },
      }, current);
      syncToManifest(nodes, next, true);
      return next;
    });
  }, [nodes, syncToManifest]);

  const updateEdgeHook = (hook: string) => {
    if (!edgeHook) return;
    setEdges((current) => {
      const duplicate = current.some((edge) =>
        edge.id !== edgeHook.id
        && edge.source === edgeHook.source
        && edge.target === edgeHook.target
        && edge.label === hook
      );
      if (duplicate) return current;
      const updated = current.map((edge) =>
        edge.id === edgeHook.id
          ? { ...edge, label: hook, data: { ...edge.data, kind: "hook" } }
          : edge
      );
      syncToManifest(nodes, updated, true);
      return updated;
    });
    setEdgeHook(null);
  };

  const deleteEdge = useCallback(() => {
    if (!edgeHook) return;
    setEdges((current) => {
      const updated = current.filter((edge) => edge.id !== edgeHook.id);
      syncToManifest(nodes, updated, true);
      return updated;
    });
    setEdgeHook(null);
  }, [edgeHook, nodes, syncToManifest]);

  /**
   * Report edges are derived from report views (block -> entity), so deleting
   * the drawn edge must delete the view itself; otherwise the edge reappears on
   * the next render and the action looks broken.
   */
  const removeReportView = (viewName: string) => {
    if (!edgeHook) return;
    const blockId = edgeHook.source.startsWith("block-")
      ? edgeHook.source.slice("block-".length)
      : "";
    const blocks = (manifest.blocks || []).map((block) => {
      if (block.id !== blockId) return block;
      const views = Array.isArray(block.params?.views) ? block.params.views : [];
      return {
        ...block,
        params: {
          ...block.params,
          views: (views as Array<{ name?: string }>).filter((view) => view.name !== viewName),
        },
      };
    });
    // A report block with no views is invalid; removing its last view removes
    // the block instead of leaving the manifest in a rejected state.
    const updated = syncReportViews({
      ...manifest,
      blocks: blocks.filter((block) => block.id !== blockId || reportViewsOf(block).length > 0),
    });
    setManifest(updated);
    const graph = manifestToGraph(updated);
    setNodes(graph.nodes);
    setEdges(graph.edges);
    setEdgeHook(null);
  };

  const edgeData = edgeHook?.data;
  const edgeKind = edgeData && typeof edgeData === "object" && "kind" in edgeData
    ? String(edgeData.kind)
    : "hook";
  const edgeReportViews = edgeData && typeof edgeData === "object" && "views" in edgeData
    && Array.isArray(edgeData.views)
    ? edgeData.views.filter((view): view is string => typeof view === "string")
    : [];
  const edgeScheduleEvery = edgeData && typeof edgeData === "object" && "every" in edgeData
    && typeof edgeData.every === "string"
    ? edgeData.every
    : "";
  const edgeUsedHooks = new Set(
    edgeHook
      ? edges
        .filter((edge) =>
          edge.id !== edgeHook.id
          && edge.source === edgeHook.source
          && edge.target === edgeHook.target
        )
        .map((edge) => String(edge.label))
      : [],
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setEdgeHook(null);
    if (node.type === "entityNode") {
      const data = node.data as EntityNodeData;
      setSelectedNode({ type: "entity", data: data.entity });
    } else if (node.type === "blockNode") {
      const data = node.data as BlockNodeData;
      setSelectedNode({ type: "block", data: data.block });
    }
  }, []);

  const handleAutoLayout = () => {
    const layouted = layoutGraph(nodes, edges);
    setNodes([...layouted.nodes]);
    setEdges([...layouted.edges]);
    syncToManifest(layouted.nodes, layouted.edges, true);
  };

  const handleAddEntity = () => {
    const nextIndex = (manifest.entities || []).length + 1;
    const newEntity: BizAppEntity = {
      name: `entity${nextIndex}`,
      label: { en: `Entity ${nextIndex}`, vi: `Thuc the ${nextIndex}` },
      fields: [
        { name: "title", type: "string", required: true },
        { name: "status", type: "string" },
      ],
    };
    const updated = {
      ...manifest,
      entities: [...(manifest.entities || []), newEntity],
    };
    setManifest(updated);
    const graph = manifestToGraph(updated);
    setNodes(graph.nodes);
    setEdges(graph.edges);
    setSelectedNode({ type: "entity", data: newEntity });
  };

  const handleAddBlock = (type: string) => {
    const nextIndex = (manifest.blocks || []).length + 1;
    const existingViews = (manifest.blocks || [])
      .filter((block) => block.type === "report")
      .flatMap((block) => reportViewsOf(block));
    const params = defaultBlockParams(type, manifest.entities || [], existingViews);
    const newBlock: BizAppBlock = {
      id: `${type}${nextIndex}`,
      type,
      params,
    };
    const updated = syncReportViews({
      ...manifest,
      blocks: [...(manifest.blocks || []), newBlock],
    });
    setManifest(updated);
    const graph = manifestToGraph(updated);
    setNodes(graph.nodes);
    setEdges(graph.edges);
    setSelectedNode({ type: "block", data: newBlock });
  };

  const handleUpdateEntity = (entity: BizAppEntity) => {
    const updatedEntities = (manifest.entities || []).map((e) =>
      e.name === entity.name ? entity : e
    );
    const updated = { ...manifest, entities: updatedEntities };
    setManifest(updated);
    const graph = manifestToGraph(updated);
    setNodes(graph.nodes);
    setEdges(graph.edges);
    setSelectedNode({ type: "entity", data: entity });
  };

  const handleUpdateBlock = (block: BizAppBlock) => {
    const updatedBlocks = (manifest.blocks || []).map((b) =>
      b.id === block.id ? block : b
    );
    const updated = syncReportViews({ ...manifest, blocks: updatedBlocks });
    setManifest(updated);
    const graph = manifestToGraph(updated);
    setNodes(graph.nodes);
    setEdges(graph.edges);
    setSelectedNode({ type: "block", data: block });
  };

  const applyManifestEdit = useCallback((updated: BizAppManifest) => {
    setManifest(updated);
    const graph = manifestToGraph(updated);
    setNodes(graph.nodes);
    setEdges(graph.edges);
    setSelectedNode(null);
  }, [setManifest]);

  const handleDeleteSelected = useCallback(() => {
    if (!selectedNode) return;
    if (selectedNode.type === "entity") {
      applyManifestEdit(removeEntityFromManifest(
        manifest,
        (selectedNode.data as BizAppEntity).name,
      ));
    } else {
      applyManifestEdit(removeBlockFromManifest(
        manifest,
        (selectedNode.data as BizAppBlock).id,
      ));
    }
  }, [applyManifestEdit, manifest, selectedNode]);

  // Ctrl/Cmd+S saves; Ctrl/Cmd+Z and Shift+Ctrl/Cmd+Z undo/redo (Ctrl+Y too);
  // Delete/Backspace removes the selection; Escape closes the inspector/dialog.
  // React Flow's Backspace handler stays disabled so both paths cannot diverge.
  useEffect(() => {
    const isEditableTarget = (target: EventTarget | null): boolean => {
      if (!(target instanceof HTMLElement)) return false;
      return target.isContentEditable
        || target.tagName === "INPUT"
        || target.tagName === "TEXTAREA"
        || target.tagName === "SELECT";
    };

    const onKeyDown = (event: KeyboardEvent) => {
      const action = composerShortcut(event, {
        editable: isEditableTarget(event.target),
        hasSelection: Boolean(edgeHook || selectedNode),
      });
      if (!action) return;

      if (action === "undo" || action === "redo") {
        if (action === "undo" ? !canUndo : !canRedo) return;
        event.preventDefault();
        if (action === "undo") undo();
        else redo();
        return;
      }

      if (action === "save") {
        if (!onSaveDraft) return;
        event.preventDefault();
        if (isSaving) return;
        setIsSaving(true);
        void Promise.resolve(onSaveDraft(manifest))
          .finally(() => setIsSaving(false));
        return;
      }

      event.preventDefault();
      if (action === "delete") {
        if (edgeHook) {
          deleteEdge();
        } else {
          handleDeleteSelected();
        }
      } else {
        setEdgeHook(null);
        setSelectedNode(null);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    manifest, onSaveDraft, isSaving, edgeHook, selectedNode, handleDeleteSelected, deleteEdge,
    canUndo, canRedo, undo, redo,
  ]);

  const handleManifestJsonChange = (newManifest: BizAppManifest) => {
    const synced = syncReportViews(newManifest);
    setManifest(synced);
    const graph = manifestToGraph(synced);
    setNodes(graph.nodes);
    setEdges(graph.edges);
  };

  const switchLocale = (next: "en" | "vi") => {
    if (next === locale) return;
    router.replace(`${pathname}${window.location.search}${window.location.hash}`, { locale: next });
  };

  return (
    <div className="flex flex-col h-screen h-dvh w-full overflow-hidden bg-background">
      {/* Top Header / Toolbar */}
      <header className="flex min-h-14 shrink-0 items-center gap-2 border-b bg-card px-3 py-2 sm:px-4">
        {/* Left: back + app name. flex-1 + min-w-0 lets the title truncate
            without collapsing the toolbar into a second row. */}
        <div className="flex min-w-0 flex-1 items-center gap-2 sm:gap-3">
          <Button
            variant="ghost"
            size="sm"
            onClick={handleBack}
            className="h-8 shrink-0 gap-1.5 px-2 text-xs font-medium text-muted-foreground hover:text-foreground"
            aria-label={t("back")}
            title={t("back")}
          >
            <ArrowLeft className="size-4" />
            <span className="hidden 2xl:inline">{t("back")}</span>
          </Button>

          <div className="h-4 w-px shrink-0 bg-border" aria-hidden="true" />

          <div className="flex min-w-0 items-center gap-3">
            <div className="hidden shrink-0 rounded bg-primary/10 p-1.5 text-primary sm:block">
              <Workflow className="size-4" />
            </div>
            <div className="min-w-0 flex-1 pr-2">
              <div className="flex min-w-0 items-center gap-1">
                <h1
                  className="min-w-0 whitespace-normal break-words text-sm font-semibold leading-tight"
                  title={manifest.name?.vi || manifest.name?.en || manifest.kind || t("title")}
                >
                  {manifest.name?.vi || manifest.name?.en || manifest.kind || t("title")}
                </h1>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  className="size-6 shrink-0 text-muted-foreground hover:text-foreground"
                  onClick={openRename}
                  aria-label={t("rename")}
                  title={t("rename")}
                >
                  <Pencil className="size-3" />
                </Button>
              </div>
              <p className="mt-0.5 hidden truncate text-[11px] leading-none text-muted-foreground sm:block">
                {manifest.schema} · {manifest.kind} · v{manifest.version || 1}
                {workspaceName ? ` · ${workspaceName}` : ""}
              </p>
            </div>
          </div>
        </div>

        {/* View switcher: segmented on desktop, dropdown when space is tight. */}
        <div className="hidden shrink-0 items-center justify-center gap-0.5 rounded-lg border bg-muted/40 p-0.5 sm:gap-1 lg:flex">
          {viewOptions.map((view) => (
            <button
              key={view.id}
              type="button"
              onClick={() => setActiveTab(view.id)}
              aria-label={view.label}
              aria-pressed={activeTab === view.id}
              title={view.label}
              className={cn(
                "flex items-center gap-1.5 rounded px-2.5 py-1 text-xs font-medium transition-colors sm:px-3",
                activeTab === view.id
                  ? "bg-background text-foreground shadow-xs"
                  : "text-muted-foreground hover:text-foreground",
              )}
            >
              <view.Icon className="size-3.5" />
              <span className="hidden 2xl:inline">{view.label}</span>
            </button>
          ))}
        </div>

        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="outline"
                size="sm"
                className="h-8 shrink-0 gap-1 px-2 lg:hidden"
                aria-label={currentView.label}
                title={currentView.label}
              />
            }
          >
            <currentView.Icon className="size-3.5" />
            <span className="hidden text-xs sm:inline">{currentView.label}</span>
            <ChevronDown className="size-3.5" />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-52">
            {viewOptions.map((view) => (
              <DropdownMenuItem
                key={view.id}
                onClick={() => setActiveTab(view.id)}
                className="justify-between"
              >
                <span className="flex items-center gap-2">
                  <view.Icon className="size-4" />
                  {view.label}
                </span>
                {activeTab === view.id ? <Check className="size-4 text-primary" /> : null}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

        {/* Primary actions stay inline; secondary actions also live in the
            overflow menu so every action remains reachable at any width. */}
        <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
          {activeTab === "canvas" && (
            <Button
              variant="outline"
              size="sm"
              onClick={handleAutoLayout}
              aria-label={t("autoLayout")}
              title={t("autoLayout")}
              className="hidden h-8 gap-1.5 px-2.5 text-xs xl:inline-flex"
            >
              <LayoutTemplate className="size-3.5" />
              <span className="hidden 2xl:inline">{t("autoLayout")}</span>
            </Button>
          )}

          {onSaveDraft && (
            <Button
              variant="outline"
              size="sm"
              onClick={async () => {
                setIsSaving(true);
                try {
                  await onSaveDraft(manifest);
                } finally {
                  setIsSaving(false);
                }
              }}
              disabled={isSaving}
              aria-label={t("saveDraft")}
              aria-keyshortcuts="Control+S Meta+S"
              title={`${t("saveDraft")} (Ctrl+S / ⌘S)`}
              className="h-8 gap-1.5 px-2.5 text-xs"
            >
              {isSaving ? <Loader2 className="size-3.5 animate-spin" /> : <Save className="size-3.5" />}
              <span className="hidden 2xl:inline">{t("saveDraft")}</span>
            </Button>
          )}

          {onSaveTemplate && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => onSaveTemplate(manifest)}
              aria-label={t("saveTemplate")}
              title={t("saveTemplate")}
              className="hidden h-8 gap-1.5 px-2.5 text-xs 2xl:inline-flex"
            >
              <Share2 className="size-3.5" />
              <span className="hidden 2xl:inline">{t("saveTemplate")}</span>
            </Button>
          )}

          {onInstall && (
            <Button
              size="sm"
              onClick={() => onInstall(manifest)}
              disabled={!isValid}
              aria-label={t("install")}
              title={workspaceName ? `${t("install")}: ${workspaceName}` : t("install")}
              className="h-8 gap-1.5 px-2.5 text-xs font-semibold"
            >
              <Play className="size-3.5" />
              <span className="hidden xl:inline">{t("install")}</span>
            </Button>
          )}

          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <Button
                  variant="outline"
                  size="icon-sm"
                  className="size-8 shrink-0"
                  aria-label="More actions"
                  title="More actions"
                />
              }
            >
              <MoreHorizontal className="size-4" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              {activeTab === "canvas" && (
                <DropdownMenuItem onClick={handleAutoLayout}>
                  <LayoutTemplate className="size-4" />
                  {t("autoLayout")}
                </DropdownMenuItem>
              )}
              {onSaveTemplate && (
                <DropdownMenuItem onClick={() => onSaveTemplate(manifest)}>
                  <Share2 className="size-4" />
                  {t("saveTemplate")}
                </DropdownMenuItem>
              )}
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => changeTheme(theme === "light" ? "dark" : "light")}>
                {theme === "light" ? <Moon className="size-4" /> : <Sun className="size-4" />}
                {theme === "light" ? "Dark mode" : "Light mode"}
              </DropdownMenuItem>
              <DropdownMenuSub>
                <DropdownMenuSubTrigger>
                  <Languages className="size-4" />
                  Language
                </DropdownMenuSubTrigger>
                <DropdownMenuSubContent className="w-40">
                  {(["en", "vi"] as const).map((option) => (
                    <DropdownMenuItem
                      key={option}
                      onClick={() => switchLocale(option)}
                      className="justify-between"
                    >
                      {option === "en" ? "English" : "Tiếng Việt"}
                      {locale === option ? <Check className="size-4 text-primary" /> : null}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuSubContent>
              </DropdownMenuSub>
              <DropdownMenuItem onClick={openRename}>
                <Pencil className="size-4" />
                {t("rename")}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>

      {/* Main Workspace Area */}
      <div className="flex-1 flex overflow-hidden">
        {activeTab === "canvas" && (
          <>
            <Palette onAddEntity={handleAddEntity} onAddBlock={handleAddBlock} />

            <div className="flex-1 h-full relative">
              <ComposerValidationContext.Provider value={nodeErrors}>
                <ReactFlow
                  nodes={nodes}
                  edges={edges}
                  onNodesChange={onNodesChange}
                  onEdgesChange={onEdgesChange}
                  onNodeDragStart={onNodeDragStart}
                  onNodeDragStop={onNodeDragStop}
                  onConnect={onConnect}
                  onEdgeClick={(_: React.MouseEvent, edge: Edge) => {
                    setSelectedNode(null);
                    setEdgeHook(edge);
                  }}
                  onNodeClick={onNodeClick}
                  nodeTypes={nodeTypes}
                  colorMode={theme}
                  proOptions={{ hideAttribution: true }}
                  deleteKeyCode={null}
                  fitView
                >
                  <Background />
                  <Controls />
                </ReactFlow>
              </ComposerValidationContext.Provider>
            </div>

            <Inspector
              selectedNode={selectedNode}
              onUpdateEntity={handleUpdateEntity}
              onUpdateBlock={handleUpdateBlock}
              onDeleteNode={handleDeleteSelected}
              onClose={() => setSelectedNode(null)}
              entities={manifest.entities}
              blocks={manifest.blocks}
            />
          </>
        )}

        {activeTab === "list" && (
          <ListView
            manifest={manifest}
            onSelectEntity={(e) => {
              setSelectedNode({ type: "entity", data: e });
              setActiveTab("canvas");
            }}
            onSelectBlock={(b) => {
              setSelectedNode({ type: "block", data: b });
              setActiveTab("canvas");
            }}
            onDeleteEntity={(name) => {
              applyManifestEdit(removeEntityFromManifest(manifest, name));
            }}
            onDeleteBlock={(id) => {
              applyManifestEdit(removeBlockFromManifest(manifest, id));
            }}
            onAddEntity={handleAddEntity}
          />
        )}

        {activeTab === "json" && (
          <JsonTab manifest={manifest} onChange={handleManifestJsonChange} />
        )}
      </div>

      {/* Bottom Status / Validation Panel */}
      <ValidationPanel isValid={isValid} errors={validationErrors} isValidating={isValidating} />

      <Dialog open={Boolean(edgeHook)} onOpenChange={(open) => !open && setEdgeHook(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {edgeKind === "report"
                ? t("edge.reportTitle")
                : edgeKind === "schedule"
                  ? t("edge.scheduleTitle")
                  : edgeKind === "hook"
                    ? t("edge.hookTitle")
                    : t("edge.title")}
            </DialogTitle>
            <DialogDescription>
              {edgeKind === "report"
                ? t("edge.reportDescription", {
                  entity: String(edgeHook?.target ?? "").replace("entity-", ""),
                })
                : edgeKind === "schedule"
                  ? t(edgeScheduleEvery ? "edge.scheduleEvery" : "edge.scheduleTimer", {
                    block: String(edgeHook?.source ?? "").replace("block-", ""),
                    entity: String(edgeHook?.target ?? "").replace("entity-", ""),
                    every: edgeScheduleEvery,
                  })
                  : edgeKind === "hook"
                    ? t("edge.hookDescription")
                    : t("edge.derivedDescription")}
            </DialogDescription>
          </DialogHeader>
          {edgeKind === "report" && (
            <div className="space-y-2">
              {edgeReportViews.map((viewName) => (
                <div
                  key={viewName}
                  className="flex items-center justify-between gap-3 rounded-md border px-3 py-2 text-sm"
                >
                  <span className="truncate">{viewName}</span>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => removeReportView(viewName)}
                  >
                    {t("inspector.remove")}
                  </Button>
                </div>
              ))}
            </div>
          )}
          {edgeKind === "hook" && (
            <div className="flex flex-wrap gap-2">
              {LIFECYCLE_HOOKS.map((hook) => (
                <Button
                  key={hook}
                  type="button"
                  variant={String(edgeHook?.label) === hook ? "default" : "outline"}
                  disabled={edgeUsedHooks.has(hook)}
                  onClick={() => updateEdgeHook(hook)}
                >
                  {hook}
                </Button>
              ))}
            </div>
          )}
          <DialogFooter>
            {edgeKind === "hook" && (
              <Button type="button" variant="destructive" onClick={deleteEdge}>
                {t("edge.delete")}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={renameOpen} onOpenChange={setRenameOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("renameTitle")}</DialogTitle>
            <DialogDescription>{t("renameDescription")}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="grid gap-1.5">
              <Label htmlFor="bizapp-name-vi">{t("nameVi")}</Label>
              <Input
                id="bizapp-name-vi"
                value={renameVi}
                maxLength={200}
                onChange={(event) => setRenameVi(event.target.value)}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="bizapp-name-en">{t("nameEn")}</Label>
              <Input
                id="bizapp-name-en"
                value={renameEn}
                maxLength={200}
                onChange={(event) => setRenameEn(event.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setRenameOpen(false)}>
              {t("cancel")}
            </Button>
            <Button
              type="button"
              onClick={applyRename}
              disabled={!renameVi.trim() || !renameEn.trim()}
            >
              {t("renameSave")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
