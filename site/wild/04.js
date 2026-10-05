EE.addWild("04", [
 {
  "id": "04-htmx-cascading-selects",
  "engine": "htmx",
  "repo": "bigskysoftware/htmx",
  "sha": "fa978b24e75fb03c137bf2cdae4fef0e711cf8a1",
  "path": "www/content/examples/value-select.md",
  "lang": "html",
  "title": "htmx's \"Cascading Selects\" example: swap the options and nothing else",
  "prompt": "After the swap, which model is selected, and who decided? Compare with the two events our <code>drink-options-response</code> sends.",
  "notes": "<p>This is the same feature in htmx's documented examples. The <code>make</code> select fires <code>hx-get=\"/models\"</code> on its default trigger, <code>change</code> for a select. htmx sends <code>make</code> as a query parameter because the select has a <code>name</code>. The response, a bare list of <code>&lt;option&gt;</code> elements, replaces the inner HTML of <code>#models</code>, because <code>innerHTML</code> is htmx's default swap. As in ours, the option list is server HTML and never client state.</p><p>Three differences. First, the response is a fragment: sibling <code>&lt;option&gt;</code>s with no wrapper and no id. Datastar patches whole elements, so ours re-sends <code>&lt;div id=\"drink-options\"&gt;</code> with both selects inside. Second, the <em>client</em> names the target (<code>hx-target</code>); ours is named by the id in the response. Third, the new selection here is implicit. The browser selects the first option of a select it has just refilled, so changing the make always resets the model. Ours keeps a size that is still valid (mocha large → latte stays large) and sends <code>datastar-patch-signals</code> only for the keys that became invalid. <code>$size</code> and <code>$milk</code> are bound signals, so without that patch they would disagree with the DOM.</p>",
  "segments": [
   {
    "start": 14,
    "end": 29,
    "code": "<div>\n    <label >Make</label>\n    <select name=\"make\" hx-get=\"/models\" hx-target=\"#models\" hx-indicator=\".htmx-indicator\">\n      <option value=\"audi\">Audi</option>\n      <option value=\"toyota\">Toyota</option>\n      <option value=\"bmw\">BMW</option>\n    </select>\n  </div>\n  <div>\n    <label>Model</label>\n    <select id=\"models\" name=\"model\">\n      <option value=\"a1\">A1</option>\n      ...\n    </select>\n    <img class=\"htmx-indicator\" width=\"20\" src=\"/img/bars.svg\" alt=\"Saving...\">\n</div>"
   },
   {
    "start": 35,
    "end": 37,
    "code": "<option value='325i'>325i</option>\n<option value='325ix'>325ix</option>\n<option value='X5'>X5</option> "
   }
  ]
 },
 {
  "id": "04-datastar-merge-patch",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/engine/signals.ts",
  "lang": "typescript",
  "title": "<code>mergePatch</code>: why a milk-less drink sends <code>\"\"</code>, not <code>null</code>",
  "prompt": "Part 4's predict: after a switch to Espresso, <code>$milk</code> is <code>\"\"</code>. Which line would have run if the server had sent <code>{\"milk\": null}</code>?",
  "notes": "<p><code>datastar-patch-signals</code> is an RFC 7386 merge patch, and these two functions apply it. At the top level and inside nested objects, a <code>null</code> value (the <code>== null</code> test also catches <code>undefined</code>) deletes the key, unless the event said <code>onlyIfMissing</code>. Any other value is merged in, and plain objects recurse. With <code>ifMissing</code>, a leaf is written only when its key doesn't exist yet (line 747). That is the behavior behind both <code>data-signals__ifmissing</code> and our writer's <code>only-if-missing</code> option. The whole patch runs inside <code>beginBatch</code>/<code>endBatch</code>, so effects run once, after every key is applied.</p><p>So <code>{\"milk\": null}</code> would delete the signal (line 703). It wouldn't stay deleted for long: the store's proxy creates a missing signal as <code>''</code> the next time anything reads it (<code>deep</code>, line 594). The lesson sends <code>\"\"</code> directly. The signal keeps existing as a string, which is what the milk select's <code>data-bind</code> writes and reads.</p>",
  "segments": [
   {
    "start": 695,
    "end": 710,
    "code": "export const mergePatch = (\n  patch: JSONPatch,\n  { ifMissing }: MergePatchArgs = {},\n): void => {\n  beginBatch()\n  for (const key in patch) {\n    if (patch[key] == null) {\n      if (!ifMissing) {\n        delete root[key]\n      }\n    } else {\n      mergeInner(patch[key], key, root, '', ifMissing)\n    }\n  }\n  endBatch()\n}"
   },
   {
    "start": 732,
    "end": 749,
    "code": "    for (const key in patch) {\n      if (patch[key] == null) {\n        if (!ifMissing) {\n          delete targetParent[target][key]\n        }\n      } else {\n        mergeInner(\n          patch[key],\n          key,\n          targetParent[target],\n          `${prefix + target}.`,\n          ifMissing,\n        )\n      }\n    }\n  } else if (!(ifMissing && hasOwn(targetParent, target))) {\n    targetParent[target] = patch\n  }"
   }
  ]
 },
 {
  "id": "04-datastar-option-selected",
  "engine": "Datastar",
  "repo": "starfederation/datastar",
  "sha": "1efcdc3cb336ec3e9139491604e770e0329657bc",
  "path": "library/src/plugins/watchers/patchElements.ts",
  "lang": "typescript",
  "title": "<code>morphNode</code>: an option's selection moves only when its <code>selected</code> attribute does",
  "prompt": "Lessons 06–07 render the size options without <code>selected</code>, yet the customer's choice survives every re-render. Which comparison makes that safe? What happens if the server always renders <code>selected</code> on Small?",
  "notes": "<p><code>updateElementProp</code> compares the <em>presence</em> of an attribute on the old element and the new one. Only when they differ does it set the live property (<code>oldElt[name] = newEltHasAttr</code>). For an <code>&lt;option&gt;</code>, that property is <code>selected</code>. When the user picks Large, the live property changes but the attribute doesn't. An old option without <code>selected</code> and a new option without <code>selected</code> compare equal, and the user's choice is left alone. That's why lessons 06–07 can re-render the whole view without knowing <code>$size</code>.</p><p>The second question has a less obvious answer. If the server always rendered <code>selected</code> on Small, the old and new options would both carry it, and the live choice would still survive. The selection moves only when the attribute moves to a different option. Lesson 04 renders <code>selected</code> from the fitted choice, which changes exactly when the server means to move the selection. When it does change, the last segment fires <code>datastar-prop-change</code> on the enclosing select, and <code>bind.ts</code> listens for that event to copy the new value into the signal. <code>data-preserve-attr=\"selected\"</code> on the new element opts out completely.</p>",
  "segments": [
   {
    "start": 610,
    "end": 625,
    "code": "    const updateElementProp = (\n      oldElt: Element,\n      newElt: Element,\n      name: string,\n    ): boolean => {\n      const newEltHasAttr = newElt.hasAttribute(name)\n      if (\n        oldElt.hasAttribute(name) !== newEltHasAttr &&\n        !preserveAttrs.includes(name)\n      ) {\n        // @ts-expect-error - setting dynamic property for native DOM properties\n        oldElt[name] = newEltHasAttr\n        return true\n      }\n      return false\n    }"
   },
   {
    "start": 657,
    "end": 664,
    "code": "    } else if (\n      oldElt instanceof HTMLOptionElement &&\n      newElt instanceof HTMLOptionElement\n    ) {\n      shouldDispatchPropChangeEvent =\n        updateElementProp(oldElt, newElt, 'selected') ||\n        shouldDispatchPropChangeEvent\n    }"
   },
   {
    "start": 682,
    "end": 687,
    "code": "    if (shouldDispatchPropChangeEvent) {\n      const dispatchElt =\n        oldElt instanceof HTMLOptionElement ? oldElt.closest('select') : oldElt\n      dispatchElt?.dispatchEvent(\n        new Event(DATASTAR_PROP_CHANGE_EVENT, { bubbles: true }),\n      )"
   }
  ]
 },
 {
  "id": "04-idiomorph-selected",
  "engine": "idiomorph",
  "repo": "bigskysoftware/idiomorph",
  "sha": "b33cbfcec44efb59b366caf9db1fcfe63662e002",
  "path": "src/idiomorph.js",
  "lang": "javascript",
  "title": "idiomorph <code>syncBooleanAttribute</code>: compare live properties instead",
  "prompt": "Datastar compares whether the <code>selected</code> attribute is present on the old and new option. What does idiomorph compare? Which of the two lets a server re-render a select without knowing the user's current choice?",
  "notes": "<p>idiomorph reads the live <code>selected</code> property of the old option and of the newly parsed one, and if they differ it copies the new property and attribute over. The user's current selection is one of the two values being compared, so whenever it disagrees with the server's freshly parsed HTML, the server's HTML wins. That rule suits a view whose server always knows the true value. It doesn't suit Brew Board's lessons 06–07. There the drop-down is re-rendered for reasons that have nothing to do with it, such as another customer's order or a sold-out milk, and the server doesn't track the current size.</p><p>idiomorph's README lists Datastar among its users, as a project that embeds a TypeScript port, and the matching code in <code>patchElements.ts</code> still follows idiomorph's closely. Form state is where the port went its own way. With idiomorph you would either render <code>selected</code> from server state every time (lesson 04's approach), or set <code>ignoreActiveValue</code>. That option protects only the focused element and only its <code>value</code> (<code>ignoreAttribute</code>, lines 821–833), so it wouldn't cover an option at all.</p>",
  "segments": [
   {
    "start": 754,
    "end": 755,
    "code": "      } else if (is.optionElement(oldElement) && is.optionElement(newElement)) {\n        syncBooleanAttribute(oldElement, newElement, \"selected\", ctx);"
   },
   {
    "start": 783,
    "end": 812,
    "code": "    function syncBooleanAttribute(oldElement, newElement, attributeName, ctx) {\n      // @ts-ignore this function is only used on boolean attrs that are reflected as dom properties\n      const newLiveValue = newElement[attributeName],\n        // @ts-ignore ditto\n        oldLiveValue = oldElement[attributeName];\n      if (newLiveValue !== oldLiveValue) {\n        const ignoreUpdate = ignoreAttribute(\n          attributeName,\n          oldElement,\n          \"update\",\n          ctx,\n        );\n        if (!ignoreUpdate) {\n          // update attribute's associated DOM property\n          // @ts-ignore this function is only used on boolean attrs that are reflected as dom properties\n          oldElement[attributeName] = newElement[attributeName];\n        }\n        if (newLiveValue) {\n          if (!ignoreUpdate) {\n            // https://developer.mozilla.org/en-US/docs/Glossary/Boolean/HTML\n            // this is the correct way to set a boolean attribute to \"true\"\n            oldElement.setAttribute(attributeName, \"\");\n          }\n        } else {\n          if (!ignoreAttribute(attributeName, oldElement, \"remove\", ctx)) {\n            oldElement.removeAttribute(attributeName);\n          }\n        }\n      }\n    }"
   }
  ]
 }
]);
