'use client';

import React from 'react';
import Link from 'next/link';
import { Plane, Stethoscope, GraduationCap, Briefcase, Home, Sparkles, Utensils, Camera, ShoppingBag } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { motion } from 'framer-motion';

export default function Categories() {
  const { language } = useLanguage();

  const categories = [
    { id: 'tourism', icon: Plane, label: language === 'ar' ? 'سياحة وإقامة' : 'Tourism', color: 'bg-blue-50 text-blue-600' },
    { id: 'medical', icon: Stethoscope, label: language === 'ar' ? 'خدمات طبية' : 'Medical', color: 'bg-rose-50 text-rose-600' },
    { id: 'realestate', icon: Home, label: language === 'ar' ? 'عقارات' : 'Real Estate', color: 'bg-emerald-50 text-emerald-600' },
    { id: 'education', icon: GraduationCap, label: language === 'ar' ? 'تعليم وتدريب' : 'Education', color: 'bg-amber-50 text-amber-600' },
    { id: 'business', icon: Briefcase, label: language === 'ar' ? 'خدمات أعمال' : 'Business', color: 'bg-indigo-50 text-indigo-600' },
    { id: 'experiences', icon: Sparkles, label: language === 'ar' ? 'تجارب فريدة' : 'Experiences', color: 'bg-purple-50 text-purple-600' },
    { id: 'dining', icon: Utensils, label: language === 'ar' ? 'مطاعم وكافيهات' : 'Dining', color: 'bg-orange-50 text-orange-600' },
    { id: 'photography', icon: Camera, label: language === 'ar' ? 'تصوير وفنون' : 'Arts', color: 'bg-pink-50 text-pink-600' },
    { id: 'shopping', icon: ShoppingBag, label: language === 'ar' ? 'تسوق' : 'Shopping', color: 'bg-cyan-50 text-cyan-600' },
  ];

  return (
    <section className="py-8 bg-white border-b border-gray-100 sticky top-16 z-40 backdrop-blur-md bg-white/80">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center gap-8 overflow-x-auto hide-scrollbar pb-2">
          {categories.map((category, index) => {
            const Icon = category.icon;
            return (
              <motion.div
                key={category.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: index * 0.05 }}
              >
                <Link
                  href={`/?category=${category.id}`}
                  className="flex flex-col items-center gap-2 group min-w-[80px]"
                >
                  <div className={`w-12 h-12 rounded-2xl ${category.color} flex items-center justify-center transition-all duration-300 group-hover:scale-110 group-hover:shadow-lg group-active:scale-95`}>
                    <Icon className="w-6 h-6" />
                  </div>
                  <span className="text-xs font-bold text-gray-500 group-hover:text-gray-900 transition-colors whitespace-nowrap">
                    {category.label}
                  </span>
                  <div className="h-0.5 w-0 bg-emerald-600 transition-all duration-300 group-hover:w-full rounded-full" />
                </Link>
              </motion.div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
