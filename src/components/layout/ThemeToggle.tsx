'use client';

import React, { useEffect, useState } from 'react';
import { Sun, Moon } from 'lucide-react';
import { Button } from '@/components/ui/Button';

export const ThemeToggle: React.FC = () => {
    const [theme, setTheme] = useState<'light' | 'dark' | null>(null);

    useEffect(() => {
        // Check localStorage or system preference on mount
        const savedTheme = localStorage.getItem('theme') as 'light' | 'dark' | null;
        const systemTheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
        const initialTheme = savedTheme || systemTheme;

        setTheme(initialTheme);
        document.documentElement.classList.toggle('dark', initialTheme === 'dark');
    }, []);

    const toggleTheme = () => {
        const newTheme = theme === 'light' ? 'dark' : 'light';
        setTheme(newTheme);
        document.documentElement.classList.toggle('dark', newTheme === 'dark');
        localStorage.setItem('theme', newTheme);
    };

    if (!theme) return <div className="h-9 w-9" />; // Avoid layout shift

    return (
        <Button
            variant="ghost"
            size="sm"
            onClick={toggleTheme}
            className="h-9 w-9 rounded-lg text-[hsl(var(--color-muted-foreground))] hover:text-[hsl(var(--color-foreground))] hover:bg-[hsl(var(--color-muted))/0.5] transition-all"
            aria-label={`Switch to ${theme === 'light' ? 'dark' : 'light'} mode`}
        >
            {theme === 'light' ? (
                <Moon className="h-5 w-5" aria-hidden="true" />
            ) : (
                <Sun className="h-5 w-5" aria-hidden="true" />
            )}
        </Button>
    );
};
