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

import { useState, useCallback, useEffect, useMemo } from "react";
import { useTranslations } from "next-intl";
import {
  ReactFlow,
  Background,
  Controls,
  applyNodeChanges,
  applyEdgeChanges,
  type Node,
  type Edge,
  type NodeChange,
  type EdgeChange,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import {
  ArrowLeft,
  Code,
  LayoutTemplate,
  List as ListIcon,
  Loader2,
  Moon,
  Play,
  Save,
  Share2,
  Sun,
  Workflow,
} from "lucide-react";

import type { BizAppManifest, BizAppEntity, BizAppBlock } from "@/types";
import { validateBizAppManifestApi } from "@/lib/bizapp-api";
import { useRouter } from "@/i18n/navigation";
import { useApiClient } from "@/hooks/useApiClient";
import { useTheme, type Theme } from "@/hooks/useTheme";
import { transitionTheme } from "@/lib/theme-transition";
import { LanguageSwitcher } from "@/components/i18n/LanguageSwitcher";
import { liquidGlassMenuStyle } from "@/components/i18n/liquidGlassMenuStyle";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { NodeEntity } from "./NodeEntity";
import { NodeBlock } from "./NodeBlock";
import { Palette } from "./Palette";
import { Inspector } from "./Inspector";
import { ValidationPanel } from "./ValidationPanel";
import { JsonTab } from "./JsonTab";
import { ListView } from "./ListView";
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
  const router = useRouter();
  const { request } = useApiClient();
  const { theme, setTheme } = useTheme();
  const languageMenuStyle = useMemo(() => liquidGlassMenuStyle(theme), [theme]);

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
  const [manifest, setManifest] = useState<BizAppManifest>(initialManifest);

  const [nodes, setNodes] = useState<Node[]>(() => manifestToGraph(initialManifest).nodes);
  const [edges, setEdges] = useState<Edge[]>(() => manifestToGraph(initialManifest).edges);

  const [selectedNode, setSelectedNode] = useState<{
    type: "entity" | "block";
    data: BizAppEntity | BizAppBlock;
  } | null>(null);

  const [isValidating, setIsValidating] = useState(false);
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const [isValid, setIsValid] = useState(true);
  const [isSaving, setIsSaving] = useState(false);

  const nodeTypes = useMemo(() => ({
    entityNode: NodeEntity,
    blockNode: NodeBlock,
  }), []);

  // Synchronize graph to manifest
  const syncToManifest = useCallback((currentNodes: Node[], currentEdges: Edge[]) => {
    const updated = graphToManifest(currentNodes, currentEdges, manifest);
    setManifest(updated);
  }, [manifest]);

  // Debounced server-side validation against POST /api/v1/bizapps/validate
  useEffect(() => {
    let active = true;
    const timer = setTimeout(async () => {
      setIsValidating(true);
      try {
        const data = await validateBizAppManifestApi(request, manifest);
        if (!active) return;
        setIsValid(Boolean(data.ok));
        setValidationErrors(Array.isArray(data.errors) ? data.errors : []);
      } catch (err) {
        if (!active) return;
        setIsValid(false);
        setValidationErrors([err instanceof Error ? err.message : "Validation failed"]);
      } finally {
        if (active) setIsValidating(false);
      }
    }, 400);

    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [manifest, request]);

  const onNodesChange = useCallback((changes: NodeChange[]) => {
    setNodes((nds) => {
      const updated = applyNodeChanges(changes, nds);
      syncToManifest(updated, edges);
      return updated;
    });
  }, [edges, syncToManifest]);

  const onEdgesChange = useCallback((changes: EdgeChange[]) => {
    setEdges((eds) => {
      const updated = applyEdgeChanges(changes, eds);
      syncToManifest(nodes, updated);
      return updated;
    });
  }, [nodes, syncToManifest]);

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
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
    syncToManifest(layouted.nodes, layouted.edges);
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
    const newBlock: BizAppBlock = {
      id: `${type}${nextIndex}`,
      type,
      params: {},
    };
    const updated = {
      ...manifest,
      blocks: [...(manifest.blocks || []), newBlock],
    };
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
    const updated = { ...manifest, blocks: updatedBlocks };
    setManifest(updated);
    const graph = manifestToGraph(updated);
    setNodes(graph.nodes);
    setEdges(graph.edges);
    setSelectedNode({ type: "block", data: block });
  };

  const handleDeleteSelected = () => {
    if (!selectedNode) return;
    if (selectedNode.type === "entity") {
      const eName = (selectedNode.data as BizAppEntity).name;
      const updated = {
        ...manifest,
        entities: (manifest.entities || []).filter((e) => e.name !== eName),
      };
      setManifest(updated);
      const graph = manifestToGraph(updated);
      setNodes(graph.nodes);
      setEdges(graph.edges);
      setSelectedNode(null);
    } else {
      const bId = (selectedNode.data as BizAppBlock).id;
      const updated = {
        ...manifest,
        blocks: (manifest.blocks || []).filter((b) => b.id !== bId),
      };
      setManifest(updated);
      const graph = manifestToGraph(updated);
      setNodes(graph.nodes);
      setEdges(graph.edges);
      setSelectedNode(null);
    }
  };

  const handleManifestJsonChange = (newManifest: BizAppManifest) => {
    setManifest(newManifest);
    const graph = manifestToGraph(newManifest);
    setNodes(graph.nodes);
    setEdges(graph.edges);
  };

  return (
    <div className="flex flex-col h-screen h-dvh w-full overflow-hidden bg-background">
      {/* Top Header / Toolbar */}
      <header className="flex items-center justify-between px-3 sm:px-4 py-2 border-b bg-card gap-2 sm:gap-4 shrink-0 h-14">
        {/* Left: Back button + Manifest Title */}
        <div className="flex items-center gap-2 sm:gap-3 min-w-0">
          <Button
            variant="ghost"
            size="sm"
            onClick={handleBack}
            className="h-8 gap-1.5 px-2 text-xs font-medium text-muted-foreground hover:text-foreground shrink-0"
            title={t("back")}
          >
            <ArrowLeft className="size-4" />
            <span className="hidden sm:inline">{t("back")}</span>
          </Button>

          <div className="h-4 w-px bg-border shrink-0" aria-hidden="true" />

          <div className="flex items-center gap-2 min-w-0">
            <div className="p-1.5 bg-primary/10 rounded text-primary shrink-0">
              <Workflow className="size-4" />
            </div>
            <div className="min-w-0">
              <h1 className="text-sm font-semibold truncate leading-tight">
                {manifest.name?.vi || manifest.name?.en || manifest.kind || t("title")}
              </h1>
              <p className="text-[11px] text-muted-foreground truncate leading-none mt-0.5">
                {manifest.schema} · {manifest.kind} · v{manifest.version || 1}
              </p>
            </div>
          </div>
        </div>

        {/* Center: View Switcher Tabs */}
        <div className="flex items-center gap-1 border rounded-lg p-0.5 bg-muted/40 shrink-0">
          <button
            type="button"
            onClick={() => setActiveTab("canvas")}
            className={cn(
              "flex items-center gap-1.5 px-2.5 sm:px-3 py-1 rounded text-xs font-medium transition-colors",
              activeTab === "canvas" ? "bg-background shadow-xs text-foreground" : "text-muted-foreground hover:text-foreground"
            )}
          >
            <Workflow className="size-3.5" />
            <span className="hidden sm:inline">{t("canvasTab")}</span>
          </button>
          <button
            type="button"
            onClick={() => setActiveTab("list")}
            className={cn(
              "flex items-center gap-1.5 px-2.5 sm:px-3 py-1 rounded text-xs font-medium transition-colors",
              activeTab === "list" ? "bg-background shadow-xs text-foreground" : "text-muted-foreground hover:text-foreground"
            )}
          >
            <ListIcon className="size-3.5" />
            <span className="hidden sm:inline">{t("listTab")}</span>
          </button>
          <button
            type="button"
            onClick={() => setActiveTab("json")}
            className={cn(
              "flex items-center gap-1.5 px-2.5 sm:px-3 py-1 rounded text-xs font-medium transition-colors",
              activeTab === "json" ? "bg-background shadow-xs text-foreground" : "text-muted-foreground hover:text-foreground"
            )}
          >
            <Code className="size-3.5" />
            <span className="hidden sm:inline">{t("jsonTab")}</span>
          </button>
        </div>

        {/* Right: Actions + Theme Toggle + Language Switcher */}
        <div className="flex items-center gap-1.5 sm:gap-2 shrink-0">
          {activeTab === "canvas" && (
            <Button variant="outline" size="sm" onClick={handleAutoLayout} className="h-8 text-xs gap-1.5 px-2.5 hidden md:inline-flex">
              <LayoutTemplate className="size-3.5" />
              <span>{t("autoLayout")}</span>
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
              className="h-8 text-xs gap-1.5 px-2.5"
            >
              {isSaving ? <Loader2 className="size-3.5 animate-spin" /> : <Save className="size-3.5" />}
              <span className="hidden sm:inline">{t("saveDraft")}</span>
            </Button>
          )}

          {onSaveTemplate && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => onSaveTemplate(manifest)}
              className="h-8 text-xs gap-1.5 px-2.5 hidden sm:inline-flex"
            >
              <Share2 className="size-3.5" />
              <span>{t("saveTemplate")}</span>
            </Button>
          )}

          {onInstall && (
            <Button
              size="sm"
              onClick={() => onInstall(manifest)}
              disabled={!isValid}
              className="h-8 text-xs gap-1.5 font-semibold px-2.5"
            >
              <Play className="size-3.5" />
              <span>{t("install")}</span>
            </Button>
          )}

          <div className="h-4 w-px bg-border mx-1 shrink-0" aria-hidden="true" />

          {/* Theme Toggle */}
          <button
            type="button"
            className="flex h-8 w-8 items-center justify-center rounded-lg border border-border/60 bg-muted/40 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            onClick={() => changeTheme(theme === "light" ? "dark" : "light")}
            aria-label={theme === "light" ? "Switch to dark mode" : "Switch to light mode"}
            title={theme === "light" ? "Switch to dark mode" : "Switch to light mode"}
          >
            {theme === "light" ? <Moon className="size-3.5" /> : <Sun className="size-3.5" />}
          </button>

          {/* Language Switcher */}
          <LanguageSwitcher
            className="flex h-8 items-center gap-1.5 rounded-lg border border-border/60 bg-muted/40 px-2 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            menuClassName="ov-language-menu"
            menuStyle={languageMenuStyle}
            variant={theme === "light" ? "light" : "dark"}
          />
        </div>
      </header>

      {/* Main Workspace Area */}
      <div className="flex-1 flex overflow-hidden">
        {activeTab === "canvas" && (
          <>
            <Palette onAddEntity={handleAddEntity} onAddBlock={handleAddBlock} />

            <div className="flex-1 h-full relative">
              <ReactFlow
                nodes={nodes}
                edges={edges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onNodeClick={onNodeClick}
                nodeTypes={nodeTypes}
                colorMode={theme}
                fitView
              >
                <Background />
                <Controls />
              </ReactFlow>
            </div>

            <Inspector
              selectedNode={selectedNode}
              onUpdateEntity={handleUpdateEntity}
              onUpdateBlock={handleUpdateBlock}
              onDeleteNode={handleDeleteSelected}
              onClose={() => setSelectedNode(null)}
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
              setManifest({
                ...manifest,
                entities: (manifest.entities || []).filter((e) => e.name !== name),
              });
            }}
            onDeleteBlock={(id) => {
              setManifest({
                ...manifest,
                blocks: (manifest.blocks || []).filter((b) => b.id !== id),
              });
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
    </div>
  );
}
